module io.vidocq.mansart.data.processor {
    requires java.compiler;
    requires io.vidocq.mansart.data.dialect.spi;
    requires io.vidocq.mansart.data.core;

    exports io.vidocq.mansart.data.processor;

    provides javax.annotation.processing.Processor
            with io.vidocq.mansart.data.processor.MansartProcessor;
}
