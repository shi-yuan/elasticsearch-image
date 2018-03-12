package org.elasticsearch.index.mapper.image;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import net.semanticmetadata.lire.imageanalysis.LireFeature;
import net.semanticmetadata.lire.indexing.hashing.BitSampling;
import net.semanticmetadata.lire.indexing.hashing.LocalitySensitiveHashing;
import net.semanticmetadata.lire.utils.ImageUtils;
import net.semanticmetadata.lire.utils.SerializationUtils;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.ElasticsearchImageProcessException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.threadpool.ThreadPool;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import static org.elasticsearch.index.mapper.MapperBuilders.binaryField;
import static org.elasticsearch.index.mapper.MapperBuilders.stringField;


public class ImageMapper extends FieldMapper {

    private static ESLogger logger = ESLoggerFactory.getLogger(ImageMapper.class.getName());

    public static final int MAX_IMAGE_DIMENSION = 1024;

    public static final String CONTENT_TYPE = "image";

    public static final String HASH = "hash";

    public static final String FEATURE = "feature";
    public static final String METADATA = "metadata";

    public static final String BIT_SAMPLING_FILE = "/hash/LshBitSampling.obj";
    public static final String LSH_HASH_FILE = "/hash/lshHashFunctions.obj";

    static {
        try {
            BitSampling.readHashFunctions(ImageMapper.class.getResourceAsStream(BIT_SAMPLING_FILE));
            LocalitySensitiveHashing.readHashFunctions(ImageMapper.class.getResourceAsStream(LSH_HASH_FILE));
        } catch (IOException e) {
            logger.error("Failed to initialize hash function", e);
        }
    }

    public static class Defaults {

        public static final ImageFieldType FIELD_TYPE = new ImageFieldType();
        public static final ContentPath.Type PATH_TYPE = ContentPath.Type.FULL;

        static {
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.freeze();
        }
    }

    static final class ImageFieldType extends MappedFieldType {

        public ImageFieldType() {
        }

        protected ImageFieldType(ImageFieldType ref) {
            super(ref);
        }

        @Override
        public ImageFieldType clone() {
            return new ImageFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public String value(Object value) {
            return value == null ? null : value.toString();
        }
    }

    public static class Builder extends FieldMapper.Builder<Builder, ImageMapper> {

        private ContentPath.Type pathType = Defaults.PATH_TYPE;

        private ThreadPool threadPool;

        private Map<FeatureEnum, Map<String, Object>> features = new HashMap<>();

        private Map<String, FieldMapper.Builder> metadataBuilders = new HashMap<>();

        public Builder(String name, ThreadPool threadPool) {
            super(name, new ImageFieldType(), new ImageFieldType());
            this.threadPool = threadPool;
            this.builder = this;
        }

        public Builder addFeature(FeatureEnum featureEnum, Map<String, Object> featureMap) {
            this.features.put(featureEnum, featureMap);
            return this;
        }

        public Builder addMetadata(String metadata, FieldMapper.Builder<Builder, ImageMapper> metadataBuilder) {
            this.metadataBuilders.put(metadata, metadataBuilder);
            return this;
        }

        @Override
        public ImageMapper build(BuilderContext context) {
            ContentPath.Type origPathType = context.path().pathType();
            context.path().pathType(pathType);

            Map<String, FieldMapper> featureMappers = new HashMap<>();
            Map<String, FieldMapper> hashMappers = new HashMap<>();
            Map<String, FieldMapper> metadataMappers = new HashMap<>();

            context.path().add(name);
            // add feature and hash mappers
            for (FeatureEnum featureEnum : features.keySet()) {
                Map<String, Object> featureMap = features.get(featureEnum);
                String featureName = featureEnum.name();

                // add feature mapper
                featureMappers.put(featureName, binaryField(featureName).store(true).includeInAll(false).index(false).build(context));


                // add hash mapper if hash is required
                if (featureMap.containsKey(HASH)) {
                    List<String> hashes = (List<String>) featureMap.get(HASH);
                    for (String h : hashes) {
                        String hashFieldName = featureName + "." + HASH + "." + h;
                        hashMappers.put(hashFieldName, stringField(hashFieldName).store(true).includeInAll(false).index(true).build(context));
                    }
                }
            }

            // add metadata mappers
            context.path().add(METADATA);
            for (Map.Entry<String, FieldMapper.Builder> entry : metadataBuilders.entrySet()) {
                String metadataName = entry.getKey();
                FieldMapper.Builder metadataBuilder = entry.getValue();
                metadataMappers.put(metadataName, (FieldMapper) metadataBuilder.build(context));
            }
            context.path().remove();  // remove METADATA
            context.path().remove();  // remove name

            context.path().pathType(origPathType);

            MappedFieldType defaultFieldType = Defaults.FIELD_TYPE.clone();
            if (this.fieldType.indexOptions() != IndexOptions.NONE && !this.fieldType.tokenized()) {
                defaultFieldType.setOmitNorms(true);
                defaultFieldType.setIndexOptions(IndexOptions.DOCS);
                if (!this.omitNormsSet && this.fieldType.boost() == 1.0F) {
                    this.fieldType.setOmitNorms(true);
                }

                if (!this.indexOptionsSet) {
                    this.fieldType.setIndexOptions(IndexOptions.DOCS);
                }
            }

            defaultFieldType.freeze();
            this.setupFieldType(context);
            return new ImageMapper(name, threadPool, context.indexSettings(), features, featureMappers, hashMappers, metadataMappers,
                    fieldType, defaultFieldType, multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        private ThreadPool threadPool;

        public TypeParser(ThreadPool threadPool) {
            this.threadPool = threadPool;
        }

        @SuppressWarnings({"unchecked"})
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            ImageMapper.Builder builder = new ImageMapper.Builder(name, threadPool);
            Map<String, Object> features = new HashMap<>();
            Map<String, Object> metadatas = new HashMap<>();

            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = entry.getKey();
                Object fieldNode = entry.getValue();

                if (FEATURE.equals(fieldName)) {
                    features = (Map<String, Object>) fieldNode;
                    iterator.remove();
                } else if (METADATA.equals(fieldName)) {
                    metadatas = (Map<String, Object>) fieldNode;
                    iterator.remove();
                }
            }

            if (features == null || features.isEmpty()) {
                throw new ElasticsearchGenerationException("Feature not found");
            }

            // process features
            for (Map.Entry<String, Object> entry : features.entrySet()) {
                String feature = entry.getKey();
                Map<String, Object> featureMap = (Map<String, Object>) entry.getValue();

                // process hash for each feature
                if (featureMap.containsKey(HASH)) {
                    Object hashVal = featureMap.get(HASH);
                    List<String> hashes = new ArrayList<>();
                    if (hashVal instanceof List) {
                        for (String h : (List<String>) hashVal) {
                            hashes.add(HashEnum.valueOf(h).name());
                        }
                    } else if (hashVal instanceof String) {
                        hashes.add(HashEnum.valueOf((String) hashVal).name());
                    } else {
                        throw new ElasticsearchGenerationException("Malformed hash value");
                    }
                    featureMap.put(HASH, hashes);
                }

                FeatureEnum featureEnum = FeatureEnum.getByName(feature);
                builder.addFeature(featureEnum, featureMap);
            }


            // process metadata
            for (Map.Entry<String, Object> entry : metadatas.entrySet()) {
                String metadataName = entry.getKey();
                Map<String, Object> metadataMap = (Map<String, Object>) entry.getValue();
                String fieldType = (String) metadataMap.get("type");
                builder.addMetadata(metadataName, (FieldMapper.Builder) parserContext.typeParser(fieldType).parse(metadataName, metadataMap, parserContext));
            }

            return builder;
        }
    }

    private final String name;

    private final ThreadPool threadPool;

    private final Settings settings;

    private volatile ImmutableOpenMap<FeatureEnum, Map<String, Object>> features = ImmutableOpenMap.of();

    private volatile ImmutableOpenMap<String, FieldMapper> featureMappers = ImmutableOpenMap.of();

    private volatile ImmutableOpenMap<String, FieldMapper> hashMappers = ImmutableOpenMap.of();

    private volatile ImmutableOpenMap<String, FieldMapper> metadataMappers = ImmutableOpenMap.of();

    public ImageMapper(String name, ThreadPool threadPool, Settings settings, Map<FeatureEnum, Map<String, Object>> features, Map<String, FieldMapper> featureMappers,
                       Map<String, FieldMapper> hashMappers, Map<String, FieldMapper> metadataMappers,
                       MappedFieldType type, MappedFieldType defaultFieldType, MultiFields multiFields, CopyTo copyTo) {
        super(name, type, defaultFieldType, settings, multiFields, copyTo);
        this.name = name;
        this.threadPool = threadPool;
        this.settings = settings;
        if (features != null) {
            this.features = ImmutableOpenMap.builder(this.features).putAll(features).build();
        }
        if (featureMappers != null) {
            this.featureMappers = ImmutableOpenMap.builder(this.featureMappers).putAll(featureMappers).build();
        }
        if (hashMappers != null) {
            this.hashMappers = ImmutableOpenMap.builder(this.hashMappers).putAll(hashMappers).build();
        }
        if (metadataMappers != null) {
            this.metadataMappers = ImmutableOpenMap.builder(this.metadataMappers).putAll(metadataMappers).build();
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mapper parse(ParseContext context) throws IOException {
        byte[] content = null;

        XContentParser parser = context.parser();
        XContentParser.Token token = parser.currentToken();
        if (token == XContentParser.Token.VALUE_STRING) {
            content = parser.binaryValue();
        }

        if (content == null) {
            throw new MapperParsingException("No content is provided.");
        }

        final Boolean useThreadPool = settings.getAsBoolean("index.image.use_thread_pool", true);
        final Boolean ignoreMetadataError = settings.getAsBoolean("index.image.ignore_metadata_error", true);

        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        final byte[] finalContent = content;
        BufferedImage img = AccessController.doPrivileged(new PrivilegedAction<BufferedImage>() {
            @Override
            public BufferedImage run() {
                try (InputStream input = new ByteBufferStreamInput(ByteBuffer.wrap(finalContent))) {
                    return ImageIO.read(input);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        if (Math.max(img.getHeight(), img.getWidth()) > MAX_IMAGE_DIMENSION) {
            img = ImageUtils.scaleImage(img, MAX_IMAGE_DIMENSION);
        }
        final BufferedImage finalImg = img;


        final Map<FeatureEnum, LireFeature> featureExtractMap = new ConcurrentHashMap<>();

        // have multiple features, use ThreadPool to process each feature
        if (useThreadPool && features.size() > 1) {
            final CountDownLatch latch = new CountDownLatch(features.size());
            Executor executor = threadPool.generic();

            for (ObjectObjectCursor<FeatureEnum, Map<String, Object>> cursor : features) {
                final FeatureEnum featureEnum = cursor.key;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            LireFeature lireFeature = featureEnum.getFeatureClass().newInstance();
                            lireFeature.extract(finalImg);
                            featureExtractMap.put(featureEnum, lireFeature);
                        } catch (Throwable e) {
                            logger.error("Failed to extract feature from image", e);
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.debug("Interrupted extract feature from image", e);
                Thread.currentThread().interrupt();
            }
        }


        for (ObjectObjectCursor<FeatureEnum, Map<String, Object>> cursor : features) {
            FeatureEnum featureEnum = cursor.key;
            Map<String, Object> featureMap = cursor.value;

            try {
                LireFeature lireFeature;
                if (featureExtractMap.containsKey(featureEnum)) {   // already processed
                    lireFeature = featureExtractMap.get(featureEnum);
                } else {
                    lireFeature = featureEnum.getFeatureClass().newInstance();
                    lireFeature.extract(img);
                }
                byte[] parsedContent = lireFeature.getByteArrayRepresentation();

                FieldMapper featureMapper = featureMappers.get(featureEnum.name());
                featureMapper.parse(context = context.createExternalValueContext(parsedContent));
                context.doc().add(new BinaryDocValuesField(name() + "." + featureEnum.name(), new BytesRef(parsedContent)));

                // add hash if required
                if (featureMap.containsKey(HASH)) {
                    List<String> hashes = (List<String>) featureMap.get(HASH);
                    for (String h : hashes) {
                        HashEnum hashEnum = HashEnum.valueOf(h);
                        int[] hashVals = null;
                        if (hashEnum.equals(HashEnum.BIT_SAMPLING)) {
                            hashVals = BitSampling.generateHashes(lireFeature.getDoubleHistogram());
                        } else if (hashEnum.equals(HashEnum.LSH)) {
                            hashVals = LocalitySensitiveHashing.generateHashes(lireFeature.getDoubleHistogram());
                        }

                        String mapperName = featureEnum.name() + "." + HASH + "." + h;
                        FieldMapper hashMapper = hashMappers.get(mapperName);
                        hashMapper.parse(context = context.createExternalValueContext(SerializationUtils.arrayToString(hashVals)));
                    }
                }
            } catch (Exception e) {
                throw new ElasticsearchImageProcessException("Failed to index feature " + featureEnum.name(), e);
            }
        }

        // process metadata if required
        if (!metadataMappers.isEmpty()) {
            try (InputStream is = new ByteBufferStreamInput(ByteBuffer.wrap(content))) {
                Metadata metadata = ImageMetadataReader.readMetadata(is);
                for (Directory directory : metadata.getDirectories()) {
                    for (Tag tag : directory.getTags()) {
                        String metadataName = tag.getDirectoryName().toLowerCase().replaceAll("\\s+", "_") + "." +
                                tag.getTagName().toLowerCase().replaceAll("\\s+", "_");
                        if (metadataMappers.containsKey(metadataName)) {
                            FieldMapper mapper = metadataMappers.get(metadataName);
                            mapper.parse(context = context.createExternalValueContext(tag.getDescription()));
                        }
                    }
                }
            } catch (ImageProcessingException e) {
                logger.error("Failed to extract metadata from image", e);
                if (!ignoreMetadataError) {
                    throw new ElasticsearchImageProcessException("Failed to extract metadata from image", e);
                }
            }
        }

        return null;
    }

    @Override
    protected void parseCreateField(ParseContext parseContext, List<Field> fields) throws IOException {

    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<Mapper> iterator() {
        return CollectionUtils.concat(super.iterator(), featureMappers.valuesIt(), hashMappers.valuesIt(), metadataMappers.valuesIt());
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        // ignore this for now
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(simpleName());

        builder.field("type", CONTENT_TYPE);

        builder.startObject(FEATURE);
        for (ObjectObjectCursor<FeatureEnum, Map<String, Object>> cursor : features) {
            builder.field(cursor.key.name(), cursor.value);
        }
        builder.endObject();

        builder.startObject(METADATA);
        for (ObjectObjectCursor<String, FieldMapper> cursor : metadataMappers) {
            cursor.value.toXContent(builder, params);
        }
        builder.endObject();

        builder.endObject();
        return builder;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }
}
