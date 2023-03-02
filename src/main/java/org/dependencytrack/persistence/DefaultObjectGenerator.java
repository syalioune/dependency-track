/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.persistence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import org.dependencytrack.RequirementsVerifier;
import org.dependencytrack.auth.Permissions;
import org.dependencytrack.model.ConfigPropertyConstants;
import org.dependencytrack.model.License;
import org.dependencytrack.model.RepositoryType;
import org.dependencytrack.parser.spdx.json.SpdxLicenseDetailParser;
import org.dependencytrack.persistence.defaults.DefaultLicenseGroupImporter;
import org.dependencytrack.util.NotificationUtil;
import alpine.common.logging.Logger;
import alpine.model.ManagedUser;
import alpine.model.Permission;
import alpine.model.Team;
import alpine.server.auth.PasswordService;

/**
 * Creates default objects on an empty database.
 *
 * @author Steve Springett
 * @since 3.0.0
 */
public class DefaultObjectGenerator implements ServletContextListener {

    private static final Logger LOGGER = Logger.getLogger(DefaultObjectGenerator.class);

    static final String DEFAULT_ADMIN_USERNAME = "admin";

    static final String ADMIN_USERNAME_ENV_VARIABLE = "DEPENDENCY_TRACK_ADMIN_USERNAME";

    static final String DEFAULT_ADMIN_PASSWORD = "admin";

    static final String ADMIN_PASSWORD_ENV_VARIABLE = "DEPENDENCY_TRACK_ADMIN_PASSWORD";

    static final String DEFAULT_ADMIN_FULL_NAME = "Administrator";

    static final String ADMIN_FULL_NAME_ENV_VARIABLE = "DEPENDENCY_TRACK_ADMIN_FULL_NAME";

    static final String DEFAULT_ADMIN_EMAIL = "admin@localhost";

    static final String ADMIN_EMAIL_ENV_VARIABLE = "DEPENDENCY_TRACK_ADMIN_EMAIL";

    /**
     * {@inheritDoc}
     */
    @Override
    public void contextInitialized(final ServletContextEvent event) {
        LOGGER.info("Initializing default object generator");
        if (RequirementsVerifier.failedValidation()) {
            return;
        }

        loadDefaultPermissions();
        loadDefaultPersonas();
        loadDefaultLicenses();
        loadDefaultLicenseGroups();
        loadDefaultRepositories();
        loadDefaultConfigProperties();
        loadDefaultNotificationPublishers();

        try {
            new CweImporter().processCweDefinitions();
        } catch (Exception e) {
            LOGGER.error("Error adding CWEs to database");
            LOGGER.error(e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void contextDestroyed(final ServletContextEvent event) {
        /* Intentionally blank to satisfy interface */
    }

    /**
     * Loads the default licenses into the database if no license data exists.
     */
    private void loadDefaultLicenses() {
        try (QueryManager qm = new QueryManager()) {
            LOGGER.info("Synchronizing SPDX license definitions to datastore");

            final SpdxLicenseDetailParser parser = new SpdxLicenseDetailParser();
            try {
                final List<License> licenses = parser.getLicenseDefinitions();
                for (final License license : licenses) {
                    LOGGER.debug("Synchronizing: " + license.getName());
                    qm.synchronizeLicense(license, false);
                }
            } catch (IOException e) {
                LOGGER.error("An error occurred during the parsing SPDX license definitions");
                LOGGER.error(e.getMessage());
            }
            qm.commitSearchIndex(License.class);
        }
    }

    /**
     * Loads the default license groups into the database if no license groups exists.
     */
    private void loadDefaultLicenseGroups() {
        try (QueryManager qm = new QueryManager()) {
            final DefaultLicenseGroupImporter importer = new DefaultLicenseGroupImporter(qm);
            if (! importer.shouldImport()) {
                return;
            }
            LOGGER.info("Adding default license group definitions to datastore");
            try {
                importer.loadDefaults();
            } catch (IOException e) {
                LOGGER.error("An error occurred loading default license group definitions");
                LOGGER.error(e.getMessage());
            }
        }
    }

    /**
     * Loads the default permissions
     */
    private void loadDefaultPermissions() {
        try (QueryManager qm = new QueryManager()) {
            LOGGER.info("Synchronizing permissions to datastore");
            for (final Permissions permission : Permissions.values()) {
                if (qm.getPermission(permission.name()) == null) {
                    LOGGER.debug("Creating permission: " + permission.name());
                    qm.createPermission(permission.name(), permission.getDescription());
                }
            }
        }
    }

    /**
     * Loads the default users and teams
     */
    private void loadDefaultPersonas() {
        try (QueryManager qm = new QueryManager()) {
            if (!qm.getManagedUsers().isEmpty() && !qm.getTeams().isEmpty()) {
                return;
            }
            LOGGER.info("Adding default users and teams to datastore");
            String adminUsername = getEnvVariable(ADMIN_USERNAME_ENV_VARIABLE, DEFAULT_ADMIN_USERNAME);
            String adminPassword = getEnvVariable(ADMIN_PASSWORD_ENV_VARIABLE, DEFAULT_ADMIN_PASSWORD);
            String adminFullName = getEnvVariable(ADMIN_FULL_NAME_ENV_VARIABLE, DEFAULT_ADMIN_FULL_NAME);
            String adminEmail = getEnvVariable(ADMIN_EMAIL_ENV_VARIABLE, DEFAULT_ADMIN_EMAIL);

            LOGGER.debug("Creating user: "+adminUsername);
            ManagedUser admin = qm.createManagedUser(adminUsername, adminFullName, adminEmail,
                    new String(PasswordService.createHash(adminPassword.toCharArray())), DEFAULT_ADMIN_PASSWORD.equals(adminPassword), true, false);

            LOGGER.debug("Creating team: Administrators");
            final Team sysadmins = qm.createTeam("Administrators", false);
            LOGGER.debug("Creating team: Portfolio Managers");
            final Team managers = qm.createTeam("Portfolio Managers", false);
            LOGGER.debug("Creating team: Automation");
            final Team automation = qm.createTeam("Automation", true);

            final List<Permission> fullList = qm.getPermissions();

            LOGGER.debug("Assigning default permissions to teams");
            sysadmins.setPermissions(fullList);
            managers.setPermissions(getPortfolioManagersPermissions(fullList));
            automation.setPermissions(getAutomationPermissions(fullList));

            qm.persist(sysadmins);
            qm.persist(managers);
            qm.persist(automation);

            LOGGER.debug("Adding admin user to System Administrators");
            qm.addUserToTeam(admin, sysadmins);

            admin = qm.getObjectById(ManagedUser.class, admin.getId());
            admin.setPermissions(qm.getPermissions());
            qm.persist(admin);
        }
    }

    private List<Permission> getPortfolioManagersPermissions(final List<Permission> fullList) {
        final List<Permission> permissions = new ArrayList<>();
        for (final Permission permission: fullList) {
            if (permission.getName().equals(Permissions.Constants.VIEW_PORTFOLIO) ||
                    permission.getName().equals(Permissions.Constants.PORTFOLIO_MANAGEMENT)) {
                permissions.add(permission);
            }
        }
        return permissions;
    }

    private List<Permission> getAutomationPermissions(final List<Permission> fullList) {
        final List<Permission> permissions = new ArrayList<>();
        for (final Permission permission: fullList) {
            if (permission.getName().equals(Permissions.Constants.VIEW_PORTFOLIO) ||
                    permission.getName().equals(Permissions.Constants.BOM_UPLOAD)) {
                permissions.add(permission);
            }
        }
        return permissions;
    }

    /**
     * Loads the default repositories
     */
    private void loadDefaultRepositories() {
        try (QueryManager qm = new QueryManager()) {
            LOGGER.info("Synchronizing default repositories to datastore");
            qm.createRepository(RepositoryType.CPAN, "cpan-public-registry", "https://fastapi.metacpan.org/v1/", true, false);
            qm.createRepository(RepositoryType.GEM, "rubygems.org", "https://rubygems.org/", true, false);
            qm.createRepository(RepositoryType.HEX, "hex.pm", "https://hex.pm/", true, false);
            qm.createRepository(RepositoryType.MAVEN, "central", "https://repo1.maven.org/maven2/", true, false);
            qm.createRepository(RepositoryType.MAVEN, "atlassian-public", "https://packages.atlassian.com/content/repositories/atlassian-public/", true, false);
            qm.createRepository(RepositoryType.MAVEN, "jboss-releases", "https://repository.jboss.org/nexus/content/repositories/releases/", true, false);
            qm.createRepository(RepositoryType.MAVEN, "clojars", "https://repo.clojars.org/", true, false);
            qm.createRepository(RepositoryType.MAVEN, "google-android", "https://maven.google.com/", true, false);
            qm.createRepository(RepositoryType.NPM, "npm-public-registry", "https://registry.npmjs.org/", true, false);
            qm.createRepository(RepositoryType.PYPI, "pypi.org", "https://pypi.org/", true, false);
            qm.createRepository(RepositoryType.NUGET, "nuget-gallery", "https://api.nuget.org/", true, false);
            qm.createRepository(RepositoryType.COMPOSER, "packagist", "https://repo.packagist.org/", true, false);
            qm.createRepository(RepositoryType.CARGO, "crates.io", "https://crates.io", true, false);
            qm.createRepository(RepositoryType.GO_MODULES, "proxy.golang.org", "https://proxy.golang.org", true, false);
        }
    }

    /**
     * Loads the default ConfigProperty objects
     */
    private void loadDefaultConfigProperties() {
        try (QueryManager qm = new QueryManager()) {
            LOGGER.info("Synchronizing config properties to datastore");
            for (final ConfigPropertyConstants cpc : ConfigPropertyConstants.values()) {
                LOGGER.debug("Creating config property: " + cpc.getGroupName() + " / " + cpc.getPropertyName());

                if (qm.getConfigProperty(cpc.getGroupName(), cpc.getPropertyName()) == null) {
                    qm.createConfigProperty(cpc.getGroupName(), cpc.getPropertyName(), getEnvVariable(generateEnvVariableName(cpc), cpc.getDefaultPropertyValue()), cpc.getPropertyType(), cpc.getDescription());
                }
            }
        }
    }

    /**
     * Loads the default notification publishers
     */
    private void loadDefaultNotificationPublishers() {
        try (QueryManager qm = new QueryManager()) {
            LOGGER.info("Synchronizing notification publishers to datastore");
            NotificationUtil.loadDefaultNotificationPublishers(qm);
        } catch (IOException e) {
            LOGGER.error("An error occurred while synchronizing a default notification publisher", e);
        }
    }

    String generateEnvVariableName(ConfigPropertyConstants configProperty) {
        StringBuilder sb = new StringBuilder();
        sb.append(configProperty.getGroupName().toUpperCase().replaceAll("[\\-\\.]", "_"));
        sb.append("_");
        sb.append(configProperty.getPropertyName().toUpperCase().replaceAll("[\\-\\.]", "_"));
        LOGGER.debug("Environment variable name for property group "+configProperty.getGroupName()+" and property name "+configProperty.getPropertyName()+" is "+sb);
        return sb.toString();
    }

    String getEnvVariable(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
