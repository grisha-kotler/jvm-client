package net.ravendb.client.document.batches;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.ravendb.abstractions.basic.CleanCloseable;
import net.ravendb.abstractions.basic.SharpEnum;
import net.ravendb.abstractions.data.Constants;
import net.ravendb.abstractions.data.GetRequest;
import net.ravendb.abstractions.data.GetResponse;
import net.ravendb.abstractions.data.QueryResult;
import net.ravendb.abstractions.data.SuggestionQuery;
import net.ravendb.abstractions.data.SuggestionQueryResult;
import net.ravendb.abstractions.json.linq.RavenJArray;
import net.ravendb.abstractions.json.linq.RavenJObject;
import net.ravendb.abstractions.json.linq.RavenJToken;
import net.ravendb.client.shard.ShardStrategy;

import org.apache.http.HttpStatus;


public class LazySuggestOperation implements ILazyOperation {

  private final String index;
  private final SuggestionQuery suggestionQuery;
  private Object result;
  private boolean requiresRetry;
  private QueryResult queryResult;


  @Override
  public QueryResult getQueryResult() {
    return queryResult;
  }


  public void setQueryResult(QueryResult queryResult) {
    this.queryResult = queryResult;
  }

  @Override
  public Object getResult() {
    return result;
  }

  @Override
  public boolean isRequiresRetry() {
    return requiresRetry;
  }

  public LazySuggestOperation(String index, SuggestionQuery suggestionQuery) {
    this.index = index;
    this.suggestionQuery = suggestionQuery;
  }

  @SuppressWarnings("boxing")
  @Override
  public GetRequest createRequest() {
    String query = String.format("term=%s&field=%s&max=%d", suggestionQuery.getTerm(), suggestionQuery.getField(), suggestionQuery.getMaxSuggestions());
    if (suggestionQuery.getAccuracy() != null) {
      query += "&accuracy=" + String.format(Constants.getDefaultLocale(), "%.3f", suggestionQuery.getAccuracy());
    }
    if (suggestionQuery.getDistance() != null) {
      query += "&distance=" + SharpEnum.value(suggestionQuery.getDistance());
    }

    GetRequest  getRequest = new GetRequest();
    getRequest.setUrl("/suggest/" + index);
    getRequest.setQuery(query);
    return getRequest;
  }

  @SuppressWarnings("hiding")
  @Override
  public void handleResponse(GetResponse response) {
    if (response.getStatus() != HttpStatus.SC_OK && response.getStatus() != HttpStatus.SC_NOT_MODIFIED) {
      throw new IllegalStateException("Got an unexpected response code for the request: " + response.getStatus() + "\n" + response.getResult());
    }

    RavenJObject result = (RavenJObject) response.getResult();
    SuggestionQueryResult suggestionQueryResult = new SuggestionQueryResult();
    List<String> values = result.get("Suggestions").values(String.class);

    suggestionQueryResult.setSuggestions(values.toArray(new String[0]));
    this.result = suggestionQueryResult;
  }

  @SuppressWarnings("hiding")
  @Override
  public void handleResponses(GetResponse[] responses, ShardStrategy shardStrategy) {
    SuggestionQueryResult result = new SuggestionQueryResult();
    Set<String> suggestons = new LinkedHashSet<>();
    for (GetResponse item: responses) {
      RavenJObject data = (RavenJObject)item.getResult();
      for (RavenJToken suggestion : data.value(RavenJArray.class, "Suggestions")) {
        suggestons.add(suggestion.value(String.class));
      }
    }
    result.setSuggestions(suggestons.toArray(new String[0]));
    this.result = result;
  }


  @Override
  public CleanCloseable enterContext() {
    return null;
  }
}
