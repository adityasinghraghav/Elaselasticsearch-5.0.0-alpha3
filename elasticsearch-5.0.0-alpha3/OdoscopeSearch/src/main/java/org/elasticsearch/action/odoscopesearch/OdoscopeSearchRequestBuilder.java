
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
