/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.plugin.odoscopesearch;

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.odoscopesearch.OdoscopeSearchAction;
import org.elasticsearch.action.search.TransportOdoscopeSearchAction;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestModule;
import org.elasticsearch.rest.action.odoscopesearch.RestOdoscopeSearchAction;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;

import java.util.Collection;
import java.util.Collections;

public class OdoscopeSearchPlugin extends Plugin {

    public static final String NAME = "odoscope-search";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Elasticsearch Odoscope-Search Plugin";
    }

    @Override
    public Collection<Module> nodeModules() {
        return Collections.<Module>singletonList(new ConfiguredExampleModule());
    }

    public void onModule(ActionModule actionModule) {
        actionModule.registerAction(OdoscopeSearchAction.INSTANCE, TransportOdoscopeSearchAction.class);
    }

    public void onModule(RestModule restModule) {
        restModule.addRestAction(RestOdoscopeSearchAction.class);
    }

    public static class ConfiguredExampleModule extends AbstractModule {
        @Override
        protected void configure() {
            bind(SavedValues.class).asEagerSingleton();
        }
    }

}