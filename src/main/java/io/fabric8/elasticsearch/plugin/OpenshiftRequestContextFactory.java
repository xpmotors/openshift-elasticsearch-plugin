/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.elasticsearch.plugin;

import static io.fabric8.elasticsearch.plugin.KibanaIndexMode.SHARED_NON_OPS;
import static io.fabric8.elasticsearch.plugin.KibanaIndexMode.SHARED_OPS;
import static io.fabric8.elasticsearch.plugin.KibanaIndexMode.UNIQUE;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest;

import io.fabric8.elasticsearch.plugin.model.Project;
import io.fabric8.elasticsearch.plugin.model.ServiceAccount;
import io.fabric8.elasticsearch.util.RequestUtils;

/**
 * Context of information regarding a use
 */
public class OpenshiftRequestContextFactory {

    private static final Logger LOGGER = Loggers.getLogger(OpenshiftRequestContextFactory.class);

    private final OpenshiftAPIService apiService;
    private final RequestUtils utils;
    private final String[] operationsProjects;
    private final String kibanaPrefix;
    private String kibanaIndexMode;

    public OpenshiftRequestContextFactory(
            final Settings settings,
            final RequestUtils utils,
            final OpenshiftAPIService apiService) {
        this.apiService = apiService;
        this.utils = utils;
        this.operationsProjects = settings.getAsArray(ConfigurationSettings.OPENSHIFT_CONFIG_OPS_PROJECTS,
                ConfigurationSettings.DEFAULT_OPENSHIFT_OPS_PROJECTS);
        this.kibanaPrefix = settings.get(ConfigurationSettings.KIBANA_CONFIG_INDEX_NAME,
                ConfigurationSettings.DEFAULT_USER_PROFILE_PREFIX);
        this.kibanaIndexMode = settings.get(ConfigurationSettings.OPENSHIFT_KIBANA_INDEX_MODE, UNIQUE);
        if (!ArrayUtils.contains(new String[] { UNIQUE, SHARED_OPS, SHARED_NON_OPS }, kibanaIndexMode.toLowerCase())) {
            this.kibanaIndexMode = UNIQUE;
        }
        LOGGER.info("Using kibanaIndexMode: '{}'", this.kibanaIndexMode);
    }

    /**
     * Create a user context from the given request
     *
     * @param   request - The RestRequest to create from
     * @return  an OpenshiftRequestContext
     * @throws  java.lang.Exception for any exception encountered
     */
    public OpenshiftRequestContext create(final RestRequest request) throws Exception {
        logRequest(request);

        Set<Project> projects = new HashSet<>();
        boolean isClusterAdmin = false;
        String user = utils.getUser(request);
        String token = utils.getBearerToken(request);
        if (StringUtils.isNotBlank(token)){
            user = utils.assertUser(request);
            isClusterAdmin = utils.isOperationsUser(request);
            if(user.contains("\\")){
                user = user.replace("\\", "/");
            }
            projects = listProjectsFor(user, token);
            return new OpenshiftRequestContext(user, token, isClusterAdmin, projects, getKibanaIndex(user, isClusterAdmin), this.kibanaIndexMode);
        }
        LOGGER.debug("Returning EMPTY request context; either was provided client cert or empty token.");
        return OpenshiftRequestContext.EMPTY;
    }

    private void logRequest(final RestRequest request) {
        if (LOGGER.isDebugEnabled()) {
            String user = utils.getUser(request);
            String token = utils.getBearerToken(request);
            LOGGER.debug("Handling Request... {}", request.uri());
            if(LOGGER.isTraceEnabled()) {
                List<String> headers = new ArrayList<>();
                for (Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
                    if(RequestUtils.AUTHORIZATION_HEADER.equals(entry.getKey().toLowerCase())){
                        headers.add(entry.getKey() + "=Bearer <REDACTED>");
                    }else {
                        headers.add(entry.getKey() + "=" + entry.getValue());
                    }
                }
                LOGGER.trace("Request headers: {}", headers);
            }
            LOGGER.debug("Evaluating request for user '{}' with a {} token", user,
                    (StringUtils.isNotEmpty(token) ? "non-empty" : "empty"));
        }
    }

    // WARNING: This function must perform authentication with the given token.
    // This
    // is the only authentication performed in this plugin. This function must
    // throw
    // an exception if the token is invalid.
    private Set<Project> listProjectsFor(final String user, final String token) throws Exception {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        return AccessController.doPrivileged(new PrivilegedAction<Set<Project>>(){

            @Override
            public Set<Project> run() {
                Set<Project> projects = apiService.projectNames(token);
                for (Iterator<Project> it = projects.iterator(); it.hasNext();) {
                    if (isBlacklistProject(it.next().getName())) {
                        it.remove();
                    }
                }
                return projects;
            }
        });
    }

    private boolean isBlacklistProject(String project) {
        return ArrayUtils.contains(operationsProjects, project.toLowerCase());
    }

    private String getKibanaIndex(String username, boolean isOpsUser) {
        return getKibanaIndex(kibanaPrefix, kibanaIndexMode, username, isOpsUser);
    }

    public static String getKibanaIndex(final String kibanaPrefix, final String kibanaIndexMode, final String username,
            final boolean isOpsUser) {
        if (StringUtils.isBlank(username)) {
            return "";
        }
        if ((SHARED_OPS.equals(kibanaIndexMode) || SHARED_NON_OPS.equals(kibanaIndexMode)) && isOpsUser) {
            return kibanaPrefix;
        }
        if (SHARED_NON_OPS.equals(kibanaIndexMode)) {
            return kibanaPrefix + "_non_ops";
        }
        return kibanaPrefix + "." + getUsernameHash(username);

    }
    
    public static String getUsernameHash(String username) {
        return DigestUtils.sha1Hex(username);
    }

    public List<OpenshiftRequestContext> getAlluserContext() {
        Set<String> users = apiService.getUsers();

        Set<Project> allProjects = apiService.projectNames();
        Map<String,Set<String>> groupUsers = apiService.getGroupUsers();
        Set<String> clusterAdmins = apiService.getClusterAdmins(groupUsers);
        Map<String,Set<Project>> userProjects = apiService.getUserProjects(groupUsers, allProjects);

        List<OpenshiftRequestContext> ret = new ArrayList<>();
        for (String user : users){
            boolean isAdmin = clusterAdmins.contains(user);
            Set<Project> projects = null;

            if(isAdmin){
                projects = allProjects;
            }else{
                projects = userProjects.getOrDefault(user,null);
                if(projects ==  null){
                    continue;
                }
            }
            Set<Project> clearProjects = projects.stream()
                    .filter(project -> !isBlacklistProject(project.getName()))
                    .collect(Collectors.toSet());
            OpenshiftRequestContext item = new OpenshiftRequestContext(user,"",isAdmin,clearProjects,getKibanaIndex(user, isAdmin),this.kibanaIndexMode);
            ret.add(item);
        }

        Map<ServiceAccount,Set<Project>> serviceAccountProjects = apiService.getServiceAccount(allProjects);
        for (ServiceAccount sa : serviceAccountProjects.keySet()){
            Set<Project> projects = serviceAccountProjects.get(sa);
            Set<Project> clearProjects = projects.stream()
                    .filter(project -> !isBlacklistProject(project.getName()))
                    .collect(Collectors.toSet());
            String saFormatName = String.format("system:serviceaccount:%s:%s", sa.getNamespace(), sa.getName());
            OpenshiftRequestContext item = new OpenshiftRequestContext(saFormatName,"",false,clearProjects,getKibanaIndex(saFormatName, false), this.kibanaIndexMode);
            ret.add(item);
        }
        return ret;
    }

    public static class OpenshiftRequestContext {

        public static final OpenshiftRequestContext EMPTY = new OpenshiftRequestContext("", "", false,
                new HashSet<Project>(), "", "");

        private final String user;
        private final String token;
        private final boolean isClusterAdmin;
        private final Set<Project> projects;
        private final String kibanaIndex;
        private final String kibanaIndexMode;

        public OpenshiftRequestContext(final String user, final String token, boolean isClusterAdmin, 
                Set<Project> projects, String kibanaIndex, final String kibanaIndexMode) {
            this.user = user;
            this.token = token;
            this.isClusterAdmin = isClusterAdmin;
            this.projects = new HashSet<>(projects);
            this.kibanaIndex = kibanaIndex;
            this.kibanaIndexMode = kibanaIndexMode;
        }

        /**
         * Method to determine if context has a none empty username and token
         * 
         * @return true if there is a non-empty user and token
         */
        public boolean isAuthenticated() {
            return StringUtils.isNotEmpty(token) && StringUtils.isNotEmpty(user);
        }

        public String getUser() {
            return this.user;
        }

        public String getToken() {
            return this.token;
        }

        public boolean isOperationsUser() {
            return isClusterAdmin;
        }

        /**
         * The Set of projects
         * 
         * @return the set of projects
         */
        public Set<Project> getProjects() {
            return projects;
        }

        public String getKibanaIndex() {
            return this.kibanaIndex;
        }

        public String getKibanaIndexMode() {
            return this.kibanaIndexMode;
        }
    }
}
