/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.job.config;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.analyze.TransportAnalyzeAction;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContentFragment;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.CustomAnalyzer;
import org.elasticsearch.index.analysis.CustomAnalyzerProvider;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.rest.action.admin.indices.RestAnalyzeAction;
import org.elasticsearch.xpack.core.ml.MlParserType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Configuration for the categorization analyzer.
 *
 * The syntax is a subset of what can be supplied to the {@linkplain RestAnalyzeAction <code>_analyze</code> endpoint}.
 * To summarise, the first option is to specify the name of an out-of-the-box analyzer:
 * <code>
 *     "categorization_analyzer" : "standard"
 * </code>
 *
 * The second option is to specify a custom analyzer by combining the <code>char_filters</code>, <code>tokenizer</code>
 * and <code>token_filters</code> fields.  In turn, each of these can be specified as the name of an out-of-the-box
 * one or as an object defining a custom one.  For example:
 * <code>
 *     "char_filters" : [
 *         "html_strip",
 *         { "type" : "pattern_replace", "pattern": "SQL: .*" }
 *     ],
 *     "tokenizer" : "thai",
 *     "token_filters" : [
 *         "lowercase",
 *         { "type" : "pattern_replace", "pattern": "^[0-9].*" }
 *     ]
 * </code>
 *
 * Unfortunately there is no easy to to reuse a subset of the <code>_analyze</code> action implementation, so much
 * of the code in this file is copied from {@link TransportAnalyzeAction}.  Unfortunately the logic required here is
 * not quite identical to that of {@link TransportAnalyzeAction}, and the required code is hard to partially reuse.
 * TODO: consider refactoring ES core to allow more reuse.
 */
public class CategorizationAnalyzerConfig implements ToXContentFragment, Writeable {

    public static final ParseField CATEGORIZATION_ANALYZER = new ParseField("categorization_analyzer");
    private static final ParseField TOKENIZER = RestAnalyzeAction.Fields.TOKENIZER;
    private static final ParseField TOKEN_FILTERS = RestAnalyzeAction.Fields.TOKEN_FILTERS;
    private static final ParseField CHAR_FILTERS = RestAnalyzeAction.Fields.CHAR_FILTERS;

    /**
     * This method is only used in the unit tests - in production code this config is always parsed as a fragment.
     */
    public static CategorizationAnalyzerConfig buildFromXContentObject(XContentParser parser, MlParserType parserType) throws IOException {

        if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
            throw new IllegalArgumentException("Expected start object but got [" + parser.currentToken() + "]");
        }
        if (parser.nextToken() != XContentParser.Token.FIELD_NAME
                || CATEGORIZATION_ANALYZER.match(parser.currentName(), parser.getDeprecationHandler()) == false) {
            throw new IllegalArgumentException("Expected [" + CATEGORIZATION_ANALYZER + "] field but got [" + parser.currentToken() + "]");
        }
        parser.nextToken();
        CategorizationAnalyzerConfig categorizationAnalyzerConfig = buildFromXContentFragment(parser, parserType);
        parser.nextToken();
        return categorizationAnalyzerConfig;
    }

    /**
     * Parse a <code>categorization_analyzer</code> from configuration or cluster state.  A custom parser is needed
     * due to the complexity of the format, with many elements able to be specified as either the name of a built-in
     * element or an object containing a custom definition.
     *
     * The parser is strict when parsing config and lenient when parsing cluster state.
     */
    static CategorizationAnalyzerConfig buildFromXContentFragment(XContentParser parser, MlParserType parserType) throws IOException {

        CategorizationAnalyzerConfig.Builder builder = new CategorizationAnalyzerConfig.Builder();

        XContentParser.Token token = parser.currentToken();
        if (token == XContentParser.Token.VALUE_STRING) {
            builder.setAnalyzer(parser.text());
        } else if (token != XContentParser.Token.START_OBJECT) {
            throw new IllegalArgumentException("[" + CATEGORIZATION_ANALYZER + "] should be analyzer's name or settings [" + token + "]");
        } else {
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (CHAR_FILTERS.match(currentFieldName, parser.getDeprecationHandler())
                        && token == XContentParser.Token.START_ARRAY) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if (token == XContentParser.Token.VALUE_STRING) {
                            builder.addCharFilter(parser.text());
                        } else if (token == XContentParser.Token.START_OBJECT) {
                            builder.addCharFilter(parser.map());
                        } else {
                            throw new IllegalArgumentException("[" + currentFieldName + "] in [" + CATEGORIZATION_ANALYZER +
                                    "] array element should contain char_filter's name or settings [" + token + "]");
                        }
                    }
                } else if (TOKENIZER.match(currentFieldName, parser.getDeprecationHandler())) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        builder.setTokenizer(parser.text());
                    } else if (token == XContentParser.Token.START_OBJECT) {
                        builder.setTokenizer(parser.map());
                    } else {
                        throw new IllegalArgumentException("[" + currentFieldName + "] in [" + CATEGORIZATION_ANALYZER +
                                "] should be tokenizer's name or settings [" + token + "]");
                    }
                } else if (TOKEN_FILTERS.match(currentFieldName, parser.getDeprecationHandler())
                        && token == XContentParser.Token.START_ARRAY) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if (token == XContentParser.Token.VALUE_STRING) {
                            builder.addTokenFilter(parser.text());
                        } else if (token == XContentParser.Token.START_OBJECT) {
                            builder.addTokenFilter(parser.map());
                        } else {
                            throw new IllegalArgumentException("[" + currentFieldName + "] in [" + CATEGORIZATION_ANALYZER +
                                    "] array element should contain token_filter's name or settings [" + token + "]");
                        }
                    }
                // Be lenient when parsing cluster state - assume unknown fields are from future versions
                } else if (parserType == MlParserType.CONFIG) {
                    throw new IllegalArgumentException("Parameter [" + currentFieldName + "] in [" + CATEGORIZATION_ANALYZER +
                            "] is unknown or of the wrong type [" + token + "]");
                }
            }
        }

        return builder.build();
    }

    /**
     * Create a <code>categorization_analyzer</code> that mimics what the tokenizer and filters built into the ML C++
     * code do.  This is the default analyzer for categorization to ensure that people upgrading from previous versions
     * get the same behaviour from their categorization jobs before and after upgrade.
     * @param categorizationFilters Categorization filters (if any) from the <code>analysis_config</code>.
     * @return The default categorization analyzer.
     */
    public static CategorizationAnalyzerConfig buildDefaultCategorizationAnalyzer(List<String> categorizationFilters) {

        CategorizationAnalyzerConfig.Builder builder = new CategorizationAnalyzerConfig.Builder();

        if (categorizationFilters != null) {
            for (String categorizationFilter : categorizationFilters) {
                Map<String, Object> charFilter = new HashMap<>();
                charFilter.put("type", "pattern_replace");
                charFilter.put("pattern", categorizationFilter);
                builder.addCharFilter(charFilter);
            }
        }

        builder.setTokenizer("ml_classic");

        Map<String, Object> tokenFilter = new HashMap<>();
        tokenFilter.put("type", "stop");
        tokenFilter.put("stopwords", Arrays.asList(
                "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
                "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun",
                "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
                "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
                "GMT", "UTC"));
        builder.addTokenFilter(tokenFilter);

        return builder.build();
    }

    /**
     * Simple store of either a name of a built-in analyzer element or a custom definition.
     */
    public static class NameOrDefinition implements ToXContentFragment, Writeable {

        // Exactly one of these two members is not null
        public final String name;
        public final Settings definition;

        NameOrDefinition(String name) {
            this.name = Objects.requireNonNull(name);
            this.definition = null;
        }

        NameOrDefinition(ParseField field, Map<String, Object> definition) {
            this.name = null;
            Objects.requireNonNull(definition);
            try {
                XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
                builder.map(definition);
                this.definition = Settings.builder().loadFromSource(Strings.toString(builder), builder.contentType()).build();
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to parse [" + definition + "] in [" + field.getPreferredName() + "]", e);
            }
        }

        NameOrDefinition(StreamInput in) throws IOException {
            name = in.readOptionalString();
            if (in.readBoolean()) {
                definition = Settings.readSettingsFromStream(in);
            } else {
                definition = null;
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeOptionalString(name);
            boolean isNotNullDefinition = this.definition != null;
            out.writeBoolean(isNotNullDefinition);
            if (isNotNullDefinition) {
                Settings.writeSettingsToStream(definition, out);
            }
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            if (definition == null) {
                builder.value(name);
            } else {
                builder.startObject();
                definition.toXContent(builder, params);
                builder.endObject();
            }
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NameOrDefinition that = (NameOrDefinition) o;
            return Objects.equals(name, that.name) &&
                    Objects.equals(definition, that.definition);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, definition);
        }

        @Override
        public String toString() {
            if (definition == null) {
                return name;
            } else {
                return definition.toDelimitedString(';');
            }
        }
    }

    private final String analyzer;
    private final List<NameOrDefinition> charFilters;
    private final NameOrDefinition tokenizer;
    private final List<NameOrDefinition> tokenFilters;

    private CategorizationAnalyzerConfig(String analyzer, List<NameOrDefinition> charFilters, NameOrDefinition tokenizer,
                                         List<NameOrDefinition> tokenFilters) {
        this.analyzer = analyzer;
        this.charFilters = Objects.requireNonNull(charFilters);
        this.tokenizer = tokenizer;
        this.tokenFilters = Objects.requireNonNull(tokenFilters);
    }

    public CategorizationAnalyzerConfig(StreamInput in) throws IOException {
        analyzer = in.readOptionalString();
        charFilters = in.readList(NameOrDefinition::new);
        tokenizer = in.readOptionalWriteable(NameOrDefinition::new);
        tokenFilters = in.readList(NameOrDefinition::new);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalString(analyzer);
        out.writeList(charFilters);
        out.writeOptionalWriteable(tokenizer);
        out.writeList(tokenFilters);
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public List<NameOrDefinition> getCharFilters() {
        return charFilters;
    }

    public NameOrDefinition getTokenizer() {
        return tokenizer;
    }

    public List<NameOrDefinition> getTokenFilters() {
        return tokenFilters;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (analyzer != null) {
            builder.field(CATEGORIZATION_ANALYZER.getPreferredName(), analyzer);
        } else {
            builder.startObject(CATEGORIZATION_ANALYZER.getPreferredName());
            if (charFilters.isEmpty() == false) {
                builder.startArray(CHAR_FILTERS.getPreferredName());
                for (NameOrDefinition charFilter : charFilters) {
                    charFilter.toXContent(builder, params);
                }
                builder.endArray();
            }
            if (tokenizer != null) {
                builder.field(TOKENIZER.getPreferredName(), tokenizer);
            }
            if (tokenFilters.isEmpty() == false) {
                builder.startArray(TOKEN_FILTERS.getPreferredName());
                for (NameOrDefinition tokenFilter : tokenFilters) {
                    tokenFilter.toXContent(builder, params);
                }
                builder.endArray();
            }
            builder.endObject();
        }
        return builder;
    }

    /**
     * Convert the config to an {@link Analyzer}.  This may be a global analyzer or a newly created custom analyzer.
     * In the case of a global analyzer the caller must NOT close it when they have finished with it.  In the case of
     * a newly created custom analyzer the caller is responsible for closing it.
     * @return The first tuple member is the {@link Analyzer}; the second indicates whether the caller is responsible
     *         for closing it.
     */
    public Tuple<Analyzer, Boolean> toAnalyzer(AnalysisRegistry analysisRegistry, Environment environment) throws IOException {
        if (analyzer != null) {
            Analyzer globalAnalyzer = analysisRegistry.getAnalyzer(analyzer);
            if (globalAnalyzer == null) {
                throw new IllegalArgumentException("Failed to find global analyzer [" + analyzer + "]");
            }
            return new Tuple<>(globalAnalyzer, Boolean.FALSE);
        } else {
            List<CharFilterFactory> charFilterFactoryList =
                    parseCharFilterFactories(analysisRegistry, environment);

            Tuple<String, TokenizerFactory> tokenizerFactory = parseTokenizerFactory(analysisRegistry,
                    environment);

            List<TokenFilterFactory> tokenFilterFactoryList = parseTokenFilterFactories(analysisRegistry,
                    environment, tokenizerFactory, charFilterFactoryList);

            return new Tuple<>(new CustomAnalyzer(tokenizerFactory.v1(), tokenizerFactory.v2(),
                    charFilterFactoryList.toArray(new CharFilterFactory[charFilterFactoryList.size()]),
                    tokenFilterFactoryList.toArray(new TokenFilterFactory[tokenFilterFactoryList.size()])), Boolean.TRUE);
        }
    }


    /**
     * Get char filter factories for each configured char filter.  Each configuration
     * element can be the name of an out-of-the-box char filter, or a custom definition.
     */
    private List<CharFilterFactory> parseCharFilterFactories(AnalysisRegistry analysisRegistry,
                                                             Environment environment) throws IOException {
        final List<CharFilterFactory> charFilterFactoryList = new ArrayList<>();
        for (NameOrDefinition charFilter : charFilters) {
            final CharFilterFactory charFilterFactory;
            if (charFilter.name != null) {
                AnalysisModule.AnalysisProvider<CharFilterFactory> charFilterFactoryFactory =
                        analysisRegistry.getCharFilterProvider(charFilter.name);
                if (charFilterFactoryFactory == null) {
                    throw new IllegalArgumentException("Failed to find global char filter under [" + charFilter.name + "]");
                }
                charFilterFactory = charFilterFactoryFactory.get(environment, charFilter.name);
            } else {
                String charFilterTypeName = charFilter.definition.get("type");
                if (charFilterTypeName == null) {
                    throw new IllegalArgumentException("Missing [type] setting for char filter: " + charFilter.definition);
                }
                AnalysisModule.AnalysisProvider<CharFilterFactory> charFilterFactoryFactory =
                        analysisRegistry.getCharFilterProvider(charFilterTypeName);
                if (charFilterFactoryFactory == null) {
                    throw new IllegalArgumentException("Failed to find global char filter under [" + charFilterTypeName + "]");
                }
                Settings settings = augmentSettings(charFilter.definition);
                // Need to set anonymous "name" of char_filter
                charFilterFactory = charFilterFactoryFactory.get(buildDummyIndexSettings(settings), environment,
                        "_anonymous_charfilter", settings);
            }
            if (charFilterFactory == null) {
                throw new IllegalArgumentException("Failed to find char filter [" + charFilter + "]");
            }
            charFilterFactoryList.add(charFilterFactory);
        }
        return charFilterFactoryList;
    }

    /**
     * Get the tokenizer factory for the configured tokenizer.  The configuration
     * can be the name of an out-of-the-box tokenizer, or a custom definition.
     */
    private Tuple<String, TokenizerFactory> parseTokenizerFactory(AnalysisRegistry analysisRegistry,
                                                                  Environment environment) throws IOException {
        final String name;
        final TokenizerFactory tokenizerFactory;
        if (tokenizer.name != null) {
            name = tokenizer.name;
            AnalysisModule.AnalysisProvider<TokenizerFactory> tokenizerFactoryFactory = analysisRegistry.getTokenizerProvider(name);
            if (tokenizerFactoryFactory == null) {
                throw new IllegalArgumentException("Failed to find global tokenizer under [" + name + "]");
            }
            tokenizerFactory = tokenizerFactoryFactory.get(environment, name);
        } else {
            String tokenizerTypeName = tokenizer.definition.get("type");
            if (tokenizerTypeName == null) {
                throw new IllegalArgumentException("Missing [type] setting for tokenizer: " + tokenizer.definition);
            }
            AnalysisModule.AnalysisProvider<TokenizerFactory> tokenizerFactoryFactory =
                    analysisRegistry.getTokenizerProvider(tokenizerTypeName);
            if (tokenizerFactoryFactory == null) {
                throw new IllegalArgumentException("Failed to find global tokenizer under [" + tokenizerTypeName + "]");
            }
            Settings settings = augmentSettings(tokenizer.definition);
            // Need to set anonymous "name" of tokenizer
            name = "_anonymous_tokenizer";
            tokenizerFactory = tokenizerFactoryFactory.get(buildDummyIndexSettings(settings), environment, name, settings);
        }
        return new Tuple<>(name, tokenizerFactory);
    }

    /**
     * Get token filter factories for each configured token filter.  Each configuration
     * element can be the name of an out-of-the-box token filter, or a custom definition.
     */
    private List<TokenFilterFactory> parseTokenFilterFactories(AnalysisRegistry analysisRegistry, Environment environment,
                                                               Tuple<String, TokenizerFactory> tokenizerFactory,
                                                               List<CharFilterFactory> charFilterFactoryList) throws IOException {
        final List<TokenFilterFactory> tokenFilterFactoryList = new ArrayList<>();
        for (NameOrDefinition tokenFilter : tokenFilters) {
            TokenFilterFactory tokenFilterFactory;
            if (tokenFilter.name != null) {
                AnalysisModule.AnalysisProvider<TokenFilterFactory> tokenFilterFactoryFactory;
                tokenFilterFactoryFactory = analysisRegistry.getTokenFilterProvider(tokenFilter.name);
                if (tokenFilterFactoryFactory == null) {
                    throw new IllegalArgumentException("Failed to find global token filter under [" + tokenFilter.name + "]");
                }
                tokenFilterFactory = tokenFilterFactoryFactory.get(environment, tokenFilter.name);
            } else {
                String filterTypeName = tokenFilter.definition.get("type");
                if (filterTypeName == null) {
                    throw new IllegalArgumentException("Missing [type] setting for token filter: " + tokenFilter.definition);
                }
                AnalysisModule.AnalysisProvider<TokenFilterFactory> tokenFilterFactoryFactory =
                        analysisRegistry.getTokenFilterProvider(filterTypeName);
                if (tokenFilterFactoryFactory == null) {
                    throw new IllegalArgumentException("Failed to find global token filter under [" + filterTypeName + "]");
                }
                Settings settings = augmentSettings(tokenFilter.definition);
                // Need to set anonymous "name" of token_filter
                tokenFilterFactory = tokenFilterFactoryFactory.get(buildDummyIndexSettings(settings), environment,
                        "_anonymous_tokenfilter", settings);
                tokenFilterFactory = CustomAnalyzerProvider.checkAndApplySynonymFilter(tokenFilterFactory, tokenizerFactory.v1(),
                        tokenizerFactory.v2(), tokenFilterFactoryList, charFilterFactoryList, environment);
            }
            if (tokenFilterFactory == null) {
                throw new IllegalArgumentException("Failed to find or create token filter [" + tokenFilter + "]");
            }
            tokenFilterFactoryList.add(tokenFilterFactory);
        }
        return tokenFilterFactoryList;
    }

    /**
     * The Elasticsearch analysis functionality is designed to work with indices.  For
     * categorization we have to pretend we've got some index settings.
     */
    private IndexSettings buildDummyIndexSettings(Settings settings) {
        IndexMetaData metaData = IndexMetaData.builder(IndexMetaData.INDEX_UUID_NA_VALUE).settings(settings).build();
        return new IndexSettings(metaData, Settings.EMPTY);
    }

    /**
     * The behaviour of Elasticsearch analyzers can vary between versions.
     * For categorization we'll always use the latest version of the text analysis.
     * The other settings are just to stop classes that expect to be associated with
     * an index from complaining.
     */
    private Settings augmentSettings(Settings settings) {
        return Settings.builder().put(settings)
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CategorizationAnalyzerConfig that = (CategorizationAnalyzerConfig) o;
        return Objects.equals(analyzer, that.analyzer) &&
                Objects.equals(charFilters, that.charFilters) &&
                Objects.equals(tokenizer, that.tokenizer) &&
                Objects.equals(tokenFilters, that.tokenFilters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(analyzer, charFilters, tokenizer, tokenFilters);
    }

    public static class Builder {

        private String analyzer;
        private List<NameOrDefinition> charFilters = new ArrayList<>();
        private NameOrDefinition tokenizer;
        private List<NameOrDefinition> tokenFilters = new ArrayList<>();

        public Builder() {
        }

        public Builder(CategorizationAnalyzerConfig categorizationAnalyzerConfig) {
            this.analyzer = categorizationAnalyzerConfig.analyzer;
            this.charFilters = new ArrayList<>(categorizationAnalyzerConfig.charFilters);
            this.tokenizer = categorizationAnalyzerConfig.tokenizer;
            this.tokenFilters = new ArrayList<>(categorizationAnalyzerConfig.tokenFilters);
        }

        public Builder setAnalyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public Builder addCharFilter(String charFilter) {
            this.charFilters.add(new NameOrDefinition(charFilter));
            return this;
        }

        public Builder addCharFilter(Map<String, Object> charFilter) {
            this.charFilters.add(new NameOrDefinition(CHAR_FILTERS, charFilter));
            return this;
        }

        public Builder setTokenizer(String tokenizer) {
            this.tokenizer = new NameOrDefinition(tokenizer);
            return this;
        }

        public Builder setTokenizer(Map<String, Object> tokenizer) {
            this.tokenizer = new NameOrDefinition(TOKENIZER, tokenizer);
            return this;
        }

        public Builder addTokenFilter(String tokenFilter) {
            this.tokenFilters.add(new NameOrDefinition(tokenFilter));
            return this;
        }

        public Builder addTokenFilter(Map<String, Object> tokenFilter) {
            this.tokenFilters.add(new NameOrDefinition(TOKEN_FILTERS, tokenFilter));
            return this;
        }

        /**
         * Create a config validating only structure, not exact analyzer/tokenizer/filter names
         */
        public CategorizationAnalyzerConfig build() {
            if (analyzer == null && tokenizer == null) {
                throw new IllegalArgumentException(CATEGORIZATION_ANALYZER + " that is not a global analyzer must specify a ["
                        + TOKENIZER + "] field");
            }
            if (analyzer != null && charFilters.isEmpty() == false) {
                throw new IllegalArgumentException(CATEGORIZATION_ANALYZER + " that is a global analyzer cannot also specify a ["
                        + CHAR_FILTERS + "] field");
            }
            if (analyzer != null && tokenizer != null) {
                throw new IllegalArgumentException(CATEGORIZATION_ANALYZER + " that is a global analyzer cannot also specify a ["
                        + TOKENIZER + "] field");
            }
            if (analyzer != null && tokenFilters.isEmpty() == false) {
                throw new IllegalArgumentException(CATEGORIZATION_ANALYZER + " that is a global analyzer cannot also specify a ["
                        + TOKEN_FILTERS + "] field");
            }
            return new CategorizationAnalyzerConfig(analyzer, charFilters, tokenizer, tokenFilters);
        }

        /**
         * Verify that the builder will build a valid config.  This is not done as part of the basic build
         * because it verifies that the names of analyzers/tokenizers/filters referenced by the config are
         * known, and the validity of these names could change over time.
         */
        public void verify(AnalysisRegistry analysisRegistry, Environment environment) throws IOException {
            Tuple<Analyzer, Boolean> tuple = build().toAnalyzer(analysisRegistry, environment);
            if (tuple.v2()) {
                tuple.v1().close();
            }
        }
    }
}
