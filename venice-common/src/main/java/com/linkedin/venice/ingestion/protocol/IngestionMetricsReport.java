/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package com.linkedin.venice.ingestion.protocol;

@SuppressWarnings("all")
public class IngestionMetricsReport extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  public static final org.apache.avro.Schema SCHEMA$ = org.apache.avro.Schema.parse("{\"type\":\"record\",\"name\":\"IngestionMetricsReport\",\"namespace\":\"com.linkedin.venice.ingestion.protocol\",\"fields\":[{\"name\":\"aggregatedMetrics\",\"type\":{\"type\":\"map\",\"values\":\"double\"},\"doc\":\"A map of string key and double map value. This map contains a set of metrics collected from isolated ingestion service.\",\"default\":{}}]}");
  /** A map of string key and double map value. This map contains a set of metrics collected from isolated ingestion service. */
  public java.util.Map<java.lang.CharSequence,java.lang.Double> aggregatedMetrics;
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call. 
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return aggregatedMetrics;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
  // Used by DatumReader.  Applications should not call. 
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: aggregatedMetrics = (java.util.Map<java.lang.CharSequence,java.lang.Double>)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
}