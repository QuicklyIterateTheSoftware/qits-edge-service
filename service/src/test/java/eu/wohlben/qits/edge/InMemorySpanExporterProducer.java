package eu.wohlben.qits.edge;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The CDI {@code SpanExporter} {@link ObservabilityIngestSpanSuppressionTest} reads spans off, in
 * place of the real OTLP exporter this process otherwise ships (qits-1432).
 *
 * <p>{@code quarkus.otel.traces.exporter} ships at Quarkus' own default, {@code cdi} — the same
 * mechanism the shipped OTLP exporter rides on — so a CDI bean of this type is wired into the SDK's
 * span pipeline exactly as a production exporter would be, with no exporter configuration of this
 * test's own. {@code %test.quarkus.otel.sdk.disabled=true} (the shipped default) keeps this inert
 * for every other suite in this module: with the SDK off, nothing ever builds the pipeline this
 * bean would join.
 *
 * <p>{@code @Unremovable} because nothing in application code injects a {@code SpanExporter} by
 * type — the only consumer is the OTel extension's own build-time lookup, which Arc's dead-bean
 * elimination runs before.
 */
@Singleton
@Unremovable
final class InMemorySpanExporterProducer {

  static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

  @Produces
  @Singleton
  SpanExporter spanExporter() {
    return EXPORTER;
  }
}
