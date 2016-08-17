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

package org.elasticsearch.action.search;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.deletebyquery.SimpleAction;
import org.elasticsearch.action.deletebyquery.SimpleRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.indices.IndexClosedException;
import org.elasticsearch.plugin.deletebyquery.SavedValues;
import org.elasticsearch.search.action.SearchServiceTransportAction;
import org.elasticsearch.search.controller.SearchPhaseController;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Map;
import java.util.Set;


import static org.elasticsearch.action.search.SearchType.COUNT;
import static org.elasticsearch.action.search.SearchType.QUERY_AND_FETCH;
import static org.elasticsearch.action.search.SearchType.SCAN;

/**
 * Delete-By-Query implementation that uses efficient scrolling and bulks deletions to delete large set of documents.
 */
public class TransportDeleteByQueryAction extends HandledTransportAction<SimpleRequest, SearchResponse> {

    private final SearchServiceTransportAction searchService;
    private final SearchPhaseController searchPhaseController;
    private final ClusterService clusterService;
    private final boolean optimizeSingleShard;

    @Inject
    public TransportDeleteByQueryAction(Settings settings, ThreadPool threadPool, SearchPhaseController searchPhaseController,
        TransportService transportService, SearchServiceTransportAction searchService,
        ClusterService clusterService, ActionFilters actionFilters, IndexNameExpressionResolver
        indexNameExpressionResolver) {
        super(settings, SimpleAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, SimpleRequest.class);
        this.searchPhaseController = searchPhaseController;
        this.searchService = searchService;
        this.clusterService = clusterService;
        this.optimizeSingleShard = this.settings.getAsBoolean("action.search.optimize_single_shard", true);
        }

    @Override
    protected void doExecute(SimpleRequest simpleRequest, ActionListener<SearchResponse> listener) {
        SearchRequest searchRequest = simpleRequest.getSearchRequest();
        // optimize search type for cases where there is only one shard group to search on
        if (optimizeSingleShard && searchRequest.searchType() != SCAN && searchRequest.searchType() != COUNT) {
        try {
        ClusterState clusterState = clusterService.state();
        String[] concreteIndices = indexNameExpressionResolver.concreteIndices(clusterState, searchRequest);
        Map<String, Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(clusterState, searchRequest.routing(), searchRequest.indices());
        int shardCount = clusterService.operationRouting().searchShardsCount(clusterState, concreteIndices, routingMap);
        if (shardCount == 1) {
        // if we only have one group, then we always want Q_A_F, no need for DFS, and no need to do THEN since we hit one shard
        searchRequest.searchType(QUERY_AND_FETCH);
        }
        } catch (IndexNotFoundException | IndexClosedException e) {
        // ignore this, we will notify the search response if its really the case
        // from the actual action
        } catch (Exception e) {
        logger.debug("failed to optimize search type, continue as normal", e);
        }
        }

        OAbstractAsyncAction searchAsyncAction = new OSearchQueryThenFetchAsyncAction(logger, searchService, clusterService, indexNameExpressionResolver, searchPhaseController, threadPool, searchRequest, listener);


    //        switch(searchRequest.searchType()) {
    //            case DFS_QUERY_THEN_FETCH:
    //                searchAsyncAction = new SearchDfsQueryThenFetchAsyncAction(logger, searchService, clusterService,
    //                        indexNameExpressionResolver, searchPhaseController, threadPool, searchRequest, listener);
    //                break;
    //            case QUERY_THEN_FETCH:
    //                searchAsyncAction = new SearchQueryThenFetchAsyncAction(this.logger, this.searchService, this.clusterService,
    //                        this.indexNameExpressionResolver, this.searchPhaseController, this.threadPool, searchRequest, listener);
    //                break;
    //            case DFS_QUERY_AND_FETCH:
    //                searchAsyncAction = new SearchDfsQueryAndFetchAsyncAction(logger, searchService, clusterService,
    //                        indexNameExpressionResolver, searchPhaseController, threadPool, searchRequest, listener);
    //                break;
    //            case QUERY_AND_FETCH:
    //                searchAsyncAction = new SearchQueryAndFetchAsyncAction(logger, searchService, clusterService,
    //                        indexNameExpressionResolver, searchPhaseController, threadPool, searchRequest, listener);
    //                break;
    //            case SCAN:
    //                searchAsyncAction = new SearchScanAsyncAction(logger, searchService, clusterService, indexNameExpressionResolver,
    //                        searchPhaseController, threadPool, searchRequest, listener);
    //                break;
    //            case COUNT:
    //                searchAsyncAction = new SearchCountAsyncAction(logger, searchService, clusterService, indexNameExpressionResolver,
    //                        searchPhaseController, threadPool, searchRequest, listener);
    //                break;
    //            default:
    //                throw new IllegalStateException("Unknown search type: [" + searchRequest.searchType() + "]");
    //        }
        searchAsyncAction.start();
        }

    }