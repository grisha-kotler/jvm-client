package net.ravendb.abstractions.data;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import net.ravendb.abstractions.basic.Reference;
import net.ravendb.abstractions.indexing.SortOptions;
import net.ravendb.abstractions.json.linq.RavenJToken;
import net.ravendb.abstractions.util.EscapingHelper;
import net.ravendb.abstractions.util.NetDateFormat;
import net.ravendb.client.utils.UrlUtils;

import org.apache.commons.lang.StringUtils;


/**
 * All the information required to query a Raven index
 */
public class IndexQuery {
  private int pageSize;

  /**
   * Initializes a new instance of the {@link IndexQuery} class.
   */
  public IndexQuery() {
    totalSize = new Reference<>();
    skippedResults = new Reference<>();
    pageSize = 128;
  }

  public IndexQuery(String query) {
    this();
    this.query = query;
  }

  private boolean pageSizeSet;
  private boolean distinct;
  private String query;
  private Reference<Integer> totalSize;
  private Map<String, SortOptions> sortHints;
  private Map<String, RavenJToken> transformerParameters;
  private int start;
  private String[] fieldsToFetch;
  private SortedField[] sortedFields;
  private Date cutoff;
  private Etag cutoffEtag;
  private boolean waitForNonStaleResultsAsOfNow;
  private boolean waitForNonStaleResults;
  private String defaultField;
  private QueryOperator defaultOperator = QueryOperator.OR;
  private boolean allowMultipleIndexEntriesForSameDocumentToResultTransformer;
  private Reference<Integer> skippedResults;
  private boolean debugOptionGetIndexEntires;
  private HighlightedField[] highlightedFields;
  private String[] highlighterPreTags;
  private String[] highlighterPostTags;
  private String highlighterKeyName;
  private String resultsTransformer;
  private boolean disableCaching;
  private boolean explainScores;
  private boolean showTimings;

  /**
   * @return Indicates if detailed timings should be calculated for various query parts (Lucene search, loading documents, transforming results). Default: false
   */
  public boolean isShowTimings() {
    return showTimings;
  }

  public Map<String, SortOptions> getSortHints() {
    return sortHints;
  }


  public void setSortHints(Map<String, SortOptions> sortHints) {
    this.sortHints = sortHints;
  }

  /**
   * Indicates if detailed timings should be calculated for various query parts (Lucene search, loading documents, transforming results). Default: false
   * @param showTimings
   */
  public void setShowTimings(boolean showTimings) {
    this.showTimings = showTimings;
  }

  public boolean isWaitForNonStaleResultsAsOfNow() {
    return waitForNonStaleResultsAsOfNow;
  }

  public boolean isWaitForNonStaleResults() {
    return waitForNonStaleResults;
  }

  public void setWaitForNonStaleResults(boolean waitForNonStaleResults) {
    this.waitForNonStaleResults = waitForNonStaleResults;
  }

  public void setWaitForNonStaleResultsAsOfNow(boolean waitForNonStaleResultsAsOfNow) {
    this.waitForNonStaleResultsAsOfNow = waitForNonStaleResultsAsOfNow;
  }

  public String getResultsTransformer() {
    return resultsTransformer;
  }

  public void setResultsTransformer(String resultsTransformer) {
    this.resultsTransformer = resultsTransformer;
  }

  /**
   * @return Whatever we should apply distinct operation to the query on the server side
   */
  public boolean isDistinct() {
    return distinct;
  }

  public void setDistinct(boolean distinct) {
    this.distinct = distinct;
  }

  public HighlightedField[] getHighlightedFields() {
    return highlightedFields;
  }

  public void setHighlightedFields(HighlightedField[] highlightedFields) {
    this.highlightedFields = highlightedFields;
  }

  public String[] getHighlighterPreTags() {
    return highlighterPreTags;
  }

  public void setHighlighterPreTags(String[] highlighterPreTags) {
    this.highlighterPreTags = highlighterPreTags;
  }

  public String[] getHighlighterPostTags() {
    return highlighterPostTags;
  }

  public void setHighlighterPostTags(String[] highlighterPostTags) {
    this.highlighterPostTags = highlighterPostTags;
  }

  /**
   * Gets highligter key name.
   */
  public String getHighlighterKeyName() {
    return highlighterKeyName;
  }

  /**
   * Sets highligter key name.
   * @param highlighterKeyName
   */
  public void setHighlighterKeyName(String highlighterKeyName) {
    this.highlighterKeyName = highlighterKeyName;
  }

  public boolean isDisableCaching() {
    return disableCaching;
  }

  public void setDisableCaching(boolean disableCaching) {
    this.disableCaching = disableCaching;
  }

  public boolean isDebugOptionGetIndexEntires() {
    return debugOptionGetIndexEntires;
  }

  public boolean isExplainScores() {
    return explainScores;
  }

  public void setExplainScores(boolean explainScores) {
    this.explainScores = explainScores;
  }

  public void setDebugOptionGetIndexEntires(boolean debugOptionGetIndexEntires) {
    this.debugOptionGetIndexEntires = debugOptionGetIndexEntires;
  }

  public boolean isAllowMultipleIndexEntriesForSameDocumentToResultTransformer() {
    return allowMultipleIndexEntriesForSameDocumentToResultTransformer;
  }

  /**
   * If set to true, this property will send multiple index entries from the same document (assuming the index project them)
   * to the result transformer function. Otherwise, those entries will be consolidate an the transformer will be
   * called just once for each document in the result set
   * @param allowMultipleIndexEntriesForSameDocumentToResultTransformer
   */
  public void setAllowMultipleIndexEntriesForSameDocumentToResultTransformer(
    boolean allowMultipleIndexEntriesForSameDocumentToResultTransformer) {
    this.allowMultipleIndexEntriesForSameDocumentToResultTransformer = allowMultipleIndexEntriesForSameDocumentToResultTransformer;
  }

  public Reference<Integer> getSkippedResults() {
    return skippedResults;
  }

  public void setSkippedResults(Reference<Integer> skippedResults) {
    this.skippedResults = skippedResults;
  }

  public QueryOperator getDefaultOperator() {
    return defaultOperator;
  }

  public void setDefaultOperator(QueryOperator defaultOperator) {
    this.defaultOperator = defaultOperator;
  }

  /**
   * Cutoff etag is used to check if the index has already process a document with the given
   * etag. Unlike Cutoff, which uses dates and is susceptible to clock synchronization issues between
   * machines, cutoff etag doesn't rely on both the server and client having a synchronized clock and
   * can work without it.
   * However, when used to query map/reduce indexes, it does NOT guarantee that the document that this
   * etag belong to is actually considered for the results.
   * What it does it guarantee that the document has been mapped, but not that the mapped values has been reduce.
   * Since map/reduce queries, by their nature,tend to be far less susceptible to issues with staleness, this is
   * considered to be an acceptable tradeoff.
   * If you need absolute no staleness with a map/reduce index, you will need to ensure synchronized clocks and
   * use the Cutoff date option, instead.
   * @return etag
   */
  public Etag getCutoffEtag() {
    return cutoffEtag;
  }

  public String getDefaultField() {
    return defaultField;
  }

  public void setDefaultField(String defaultField) {
    this.defaultField = defaultField;
  }

  public void setCutoffEtag(Etag cutoffEtag) {
    this.cutoffEtag = cutoffEtag;
  }

  public Date getCutoff() {
    return cutoff;
  }

  public void setCutoff(Date cutoff) {
    this.cutoff = cutoff;
  }

  public SortedField[] getSortedFields() {
    return sortedFields;
  }

  public void setSortedFields(SortedField[] sortedFields) {
    this.sortedFields = sortedFields;
  }

  public int getStart() {
    return start;
  }

  public void setStart(int start) {
    this.start = start;
  }

  public Map<String, RavenJToken> getTransformerParameters() {
    return transformerParameters;
  }

  public void setTransformerParameters(Map<String, RavenJToken> transformerParameters) {
    this.transformerParameters = transformerParameters;
  }

  public Reference<Integer> getTotalSize() {
    return totalSize;
  }

  /**
   * @return Whatever the page size was explicitly set or still at its default value
   */
  public boolean isPageSizeSet() {
    return pageSizeSet;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
    this.pageSizeSet = true;
  }

  public String[] getFieldsToFetch() {
    return fieldsToFetch;
  }

  public void setFieldsToFetch(String[] fieldsToFetch) {
    this.fieldsToFetch = fieldsToFetch;
  }

  public String getIndexQueryUrl(String operationUrl, String index, String operationName) {
    return getIndexQueryUrl(operationUrl, index, operationName, true, true);
  }

  public String getIndexQueryUrl(String operationUrl, String index, String operationName, boolean includePageSizeEvenIfNotExplicitlySet) {
    return getIndexQueryUrl(operationUrl, index, operationName, includePageSizeEvenIfNotExplicitlySet, true);
  }

  /**
   * Gets the index query URL.
   * @param operationUrl
   * @param index
   * @param operationName
   * @param includePageSizeEvenIfNotExplicitlySet
   * @return index query url
   */
  public String getIndexQueryUrl(String operationUrl, String index, String operationName, boolean includePageSizeEvenIfNotExplicitlySet, boolean includeQuery) {
    if (operationUrl.endsWith("/"))
      operationUrl = operationUrl.substring(0, operationUrl.length() - 1);
    StringBuilder path = new StringBuilder()
    .append(operationUrl)
    .append("/")
    .append(operationName)
    .append("/")
    .append(index);

    appendQueryString(path, includePageSizeEvenIfNotExplicitlySet, includeQuery);

    return path.toString();
  }

  public String getMinimalQueryString() {
    StringBuilder sb = new StringBuilder();
    appendMinimalQueryString(sb, true);
    return sb.toString();
  }


  public String getQueryString() {
    StringBuilder sb = new StringBuilder();
    appendQueryString(sb);
    return sb.toString();
  }

  public void appendQueryString(StringBuilder path){
    appendQueryString(path, true, true);
  }

  public void appendQueryString(StringBuilder path, boolean includePageSizeEvenIfNotExplicitlySet, boolean includeQuery) {
    path.append("?");

    appendMinimalQueryString(path, includeQuery);

    if (start != 0) {
      path.append("&start=").append(start);
    }

    if (includePageSizeEvenIfNotExplicitlySet || pageSizeSet) {
      path.append("&pageSize=").append(pageSize);
    }

    if (isAllowMultipleIndexEntriesForSameDocumentToResultTransformer()) {
      path.append("&allowMultipleIndexEntriesForSameDocumentToResultTransformer=true");
    }

    if (isDistinct()) {
      path.append("&distinct=true");
    }

    if (showTimings) {
      path.append("&showTimings=true");
    }

    if (fieldsToFetch != null) {
      for (String field: fieldsToFetch) {
        if (StringUtils.isNotEmpty(field)) {
          path.append("&fetch=").append(UrlUtils.escapeDataString(field));
        }
      }
    }

    if (sortedFields != null) {
      for (SortedField field: sortedFields) {
        if (field != null) {
          path.append("&sort=").append(field.isDescending()? "-" : "").append(UrlUtils.escapeDataString(field.getField()));
        }
      }
    }

    if (sortHints != null) {
      for(Entry<String, SortOptions> sortHint: sortHints.entrySet()) {
        path.append(String.format("&SortHint%s%s=%s", sortHint.getKey().startsWith("-") ? "" : "-", UrlUtils.escapeDataString(sortHint.getKey()), sortHint.getValue()));
      }
    }


    if (StringUtils.isNotEmpty(resultsTransformer)) {
      path.append("&resultsTransformer=").append(UrlUtils.escapeDataString(resultsTransformer));
    }

    if (transformerParameters != null) {
      for (Map.Entry<String, RavenJToken> input: transformerParameters.entrySet()) {
        path.append("&tp-").append(input.getKey()).append("=").append(input.getValue().toString());
      }
    }

    if (cutoff != null) {
      NetDateFormat sdf = new NetDateFormat();
      String cutOffAsString = UrlUtils.escapeDataString(sdf.format(cutoff));
      path.append("&cufOff=").append(cutOffAsString);
    }
    if (cutoffEtag != null) {
      path.append("&cutOffEtag=").append(cutoffEtag);
    }
    if (waitForNonStaleResultsAsOfNow) {
      path.append("&waitForNonStaleResultsAsOfNow=true");
    }
    if (highlightedFields != null) {
      for( HighlightedField field: highlightedFields) {
        path.append("&highlight=").append(field);
      }
    }
    if (highlighterPreTags != null) {
      for(String preTag: highlighterPreTags) {
        path.append("&preTags=").append(UrlUtils.escapeUriString(preTag));
      }
    }

    if (highlighterPostTags != null) {
      for (String postTag: highlighterPostTags) {
        path.append("&postTags=").append(UrlUtils.escapeUriString(postTag));
      }
    }

    if (StringUtils.isNotEmpty(highlighterKeyName)) {
      path.append("&highlighterKeyName=").append(UrlUtils.escapeDataString(highlighterKeyName));
    }

    if (debugOptionGetIndexEntires) {
      path.append("&debug=entries");
    }
  }

  private void appendMinimalQueryString(StringBuilder path, boolean appendQuery) {
    if (StringUtils.isNotEmpty(query) && appendQuery) {
      path.append("&query=").append(EscapingHelper.escapeLongDataString(query));
    }

    if (StringUtils.isNotEmpty(defaultField)) {
      path.append("&defaultField=").append(UrlUtils.escapeDataString(defaultField));
    }
    if (defaultOperator != QueryOperator.OR) {
      path.append("&operator=AND");
    }
    String vars = getCustomQueryStringVariables();
    if (StringUtils.isNotEmpty(vars)) {
      path.append(vars.startsWith("&") ? vars : ("&" + vars));
    }
  }

  protected String getCustomQueryStringVariables() {
    return "";
  }

  @Override
  public IndexQuery clone() {
    try {

      IndexQuery clone = new IndexQuery();

      clone.pageSizeSet = pageSizeSet;
      clone.query = query;
      clone.totalSize = totalSize;
      clone.transformerParameters = new HashMap<>();
      for (String key: transformerParameters.keySet()) {
        clone.transformerParameters.put(key, transformerParameters.get(key).cloneToken());
      }
      clone.start = start;
      clone.fieldsToFetch = fieldsToFetch.clone();
      clone.sortedFields = new SortedField[sortedFields.length];
      for (int i = 0 ; i <  sortedFields.length; i++) {
        clone.sortedFields[i] = sortedFields[i].clone();
      }
      clone.cutoff = cutoff;
      clone.cutoffEtag = cutoffEtag.clone();
      clone.defaultField = defaultField;
      clone.defaultOperator = defaultOperator;
      clone.skippedResults = new Reference<>(skippedResults.value);
      clone.debugOptionGetIndexEntires = debugOptionGetIndexEntires;
      clone.highlightedFields = new HighlightedField[highlightedFields.length];
      for (int i = 0; i < highlightedFields.length; i++) {
        clone.highlightedFields[i] = highlightedFields[i].clone();
      }
      clone.highlighterPreTags = highlighterPreTags.clone();
      clone.highlighterPostTags = highlighterPostTags.clone();
      clone.resultsTransformer = resultsTransformer;
      clone.disableCaching = disableCaching;

      return clone;
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String toString() {
    return query;
  }

}
