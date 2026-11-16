package eu.wohlben.qits.edge.acme;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The A RRSets the public edge owns in its DNS zone (qits-2273): one per private application host,
 * pointing at the private edge's tunnel address. Names are relative to the zone, as Hetzner spells
 * them ({@code projects.qits} in {@code wohlben.eu}).
 *
 * <p><b>Ownership is a label, and the label is the whole of it.</b> Every RRSet this side creates
 * carries {@link #OWNER_LABEL}{@code =}{@link #OWNER_VALUE}; {@link #owned()} lists only those, and
 * nothing here writes or deletes an RRSet without it. A record a person made by hand has no label
 * and is never touched.
 */
public interface AddressRecords {

  /** The ownership label's key. */
  String OWNER_LABEL = "qits-exposure";

  /** The ownership label's value: these are the private exposure's names. */
  String OWNER_VALUE = "private";

  /** The TTL every owned RRSet carries: short, so a moved tunnel address is picked up quickly. */
  int TTL = 60;

  /**
   * One RRSet as the zone holds it.
   *
   * @param name relative to the zone
   * @param type {@code A}, {@code CNAME}, ...
   * @param values the record values, in the zone's order
   */
  record RrSet(String name, String type, int ttl, Map<String, String> labels, List<String> values) {

    public RrSet {
      labels = labels == null ? Map.of() : Map.copyOf(labels);
      values = values == null ? List.of() : List.copyOf(values);
    }

    /** Whether this RRSet carries the ownership label — the one test for "ours". */
    public boolean owned() {
      return OWNER_VALUE.equals(labels.get(OWNER_LABEL));
    }
  }

  /** Every A RRSet carrying the ownership label, every page of them. */
  List<RrSet> owned() throws Exception;

  /**
   * The one RRSet of {@code type} at {@code name}, by its own path rather than a list filter — the
   * wildcard lookup and the collision check before a create. Empty when there is none.
   */
  Optional<RrSet> get(String name, String type) throws Exception;

  /** Creates the owned A RRSet {@code name} with the records {@code values} and {@link #TTL}. */
  void create(String name, List<String> values) throws Exception;

  /** Replaces the records of the A RRSet {@code name} with {@code values}. */
  void setRecords(String name, List<String> values) throws Exception;

  /** Sets the A RRSet {@code name}'s TTL to {@link #TTL}. */
  void changeTtl(String name) throws Exception;

  /** Deletes the A RRSet {@code name}; one already gone is not an error. */
  void delete(String name) throws Exception;
}
