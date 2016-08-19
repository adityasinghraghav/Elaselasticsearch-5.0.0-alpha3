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

package org.elasticsearch.action.odoscopesearch;


import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.index.query.QueryBuilders;

public class OdoscopeSearchRequestBuilder extends ActionRequestBuilder<OdoscopeSearchRequest, SearchResponse, OdoscopeSearchRequestBuilder> {

    public OdoscopeSearchRequestBuilder(ElasticsearchClient client) {
        super(client, OdoscopeSearchAction.INSTANCE, new OdoscopeSearchRequest());

        // here: a built-in query definition, a match all query

        SearchRequestBuilder searchRequestBuilder = new SearchRequestBuilder(client, SearchAction.INSTANCE);
        searchRequestBuilder
                .setQuery(QueryBuilders.matchAllQuery());

        request.setSearchRequest(searchRequestBuilder.request());
    }

    @Override
    public OdoscopeSearchRequest request() {
        return request;
    }

}
