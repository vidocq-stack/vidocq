package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

public class MediaTypeImpl extends AbstractExtensible<MediaType> implements MediaType {

    private Schema schema;
    private Map<String, Example> examples;
    private Object example;
    private Map<String, Encoding> encoding;

    @Override public Schema getSchema() { return schema; }
    @Override public void setSchema(Schema schema) { this.schema = schema; }

    @Override public Map<String, Example> getExamples() { return examples; }
    @Override public void setExamples(Map<String, Example> examples) { this.examples = examples; }

    @Override
    public MediaType addExample(String key, Example example) {
        if (example == null) return this;
        if (examples == null) examples = new LinkedHashMap<>();
        examples.put(key, example);
        return this;
    }

    @Override
    public void removeExample(String key) {
        if (examples != null) examples.remove(key);
    }

    @Override public Object getExample() { return example; }
    @Override public void setExample(Object example) { this.example = example; }

    @Override public Map<String, Encoding> getEncoding() { return encoding; }
    @Override public void setEncoding(Map<String, Encoding> encoding) { this.encoding = encoding; }

    @Override
    public MediaType addEncoding(String key, Encoding encodingItem) {
        if (encodingItem == null) return this;
        if (encoding == null) encoding = new LinkedHashMap<>();
        encoding.put(key, encodingItem);
        return this;
    }

    @Override
    public void removeEncoding(String key) {
        if (encoding != null) encoding.remove(key);
    }
}

