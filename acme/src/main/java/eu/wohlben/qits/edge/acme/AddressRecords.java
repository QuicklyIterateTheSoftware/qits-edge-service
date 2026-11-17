package eu.wohlben.qits.edge.acme;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The address RRSets the public edge owns in its DNS zone: since qits-2292 every public name, not
 * only the private ones. Names are relative to the zone, as Hetzner spells them ({@code
 * projects.qits} in {@code wohlben.eu}, {@code @} for the apex).
 *
 * <ul>
 *   <li>{@code A} (and {@code AAAA}) at the node name {@code node} and at the apex {@code @}, the
 *       node's public address — labelled {@link #PUBLIC};
 *   <li>one {@code CNAME} per public host and project door, value {@code node.<domain>.} — labelled
 *       {@link #PUBLIC};
 *   <li>one {@code A} per private application host, the private edge's tunnel address (qits-2273) —
 *       labelled {@link #PRIVATE}.
 * </ul>
 *
 * <p><b>Ownership is a label, and the label is the whole of it.</b> Every RRSet this side creates
 * carries {@link #OWNER_LABEL} with one of the two {@link #OWNER_VALUES}; {@link #owned()} lists
 * only those, and nothing here writes or deletes an RRSet without it. A record a person made by
 * hand has no label and is never touched — the qits-2292 migration of the hand-made apex, door and
 * door wildcard is the one exception, and the reconciler, not this client, decides it.
 */
public interface AddressRecords {

  /** The ownership label's key. */
  String OWNER_LABEL = "qits-exposure";

  /** The label value of the private exposure's names: tunnel {@code A} records (qits-2273). */
  String PRIVATE = "private";

  /** The label value of the public names: the node, the apex and every CNAME (qits-2292). */
  String PUBLIC = "public";

  /** Either value says "ours". */
  Set<String> OWNER_VALUES = Set.of(PRIVATE, PUBLIC);

  /** The record types this side writes; {@link #owned()} lists these and nothing else. */
  Set<String> TYPES = Set.of("A", "AAAA", "CNAME");

  /** The TTL every owned RRSet carries: short, so a moved address is picked up quickly. */
  int TTL = 60;

  /**
   * One RRSet as the zone holds it.
   *
   * @param name relative to the zone
   * @param type {@code A}, {@code AAAA}, {@code CNAME}, ...
   * @param values the record values, in the zone's order
   */
  record RrSet(String name, String type, int ttl, Map<String, String> labels, List<String> values) {

    public RrSet {
      labels = labels == null ? Map.of() : Map.copyOf(labels);
      values = values == null ? List.of() : List.copyOf(values);
    }

    /** Whether this RRSet carries the ownership label with either value — the one test for ours. */
    public boolean owned() {
      String value = labels.get(OWNER_LABEL);
      return value != null && OWNER_VALUES.contains(value);
    }

    /** The ownership label's value, or null on an RRSet that is not ours. */
    public String exposure() {
      return owned() ? labels.get(OWNER_LABEL) : null;
    }
  }

  /** Every A, AAAA and CNAME RRSet carrying the ownership label with either value, every page. */
  List<RrSet> owned() throws Exception;

  /**
   * The one RRSet of {@code type} at {@code name}, by its own path rather than a list filter — the
   * collision check before a create, and the migration's look at the hand-made records. Empty when
   * there is none.
   */
  Optional<RrSet> get(String name, String type) throws Exception;

  /**
   * Creates the RRSet {@code name}/{@code type} with the records {@code values}, {@link #TTL} and
   * {@link #OWNER_LABEL}{@code =exposure}. A CNAME value is absolute — written with its trailing
   * dot.
   */
  void create(String name, String type, List<String> values, String exposure) throws Exception;

  /** Replaces the records of the RRSet {@code name}/{@code type} with {@code values}. */
  void setRecords(String name, String type, List<String> values) throws Exception;

  /** Sets the RRSet {@code name}/{@code type}'s TTL to {@link #TTL}. */
  void changeTtl(String name, String type) throws Exception;

  /**
   * Replaces the labels of the RRSet {@code name}/{@code type} with {@code labels} — how a
   * hand-made apex whose values already are the node's is adopted (qits-2292) without a gap.
   */
  void setLabels(String name, String type, Map<String, String> labels) throws Exception;

  /** Deletes the RRSet {@code name}/{@code type}; one already gone is not an error. */
  void delete(String name, String type) throws Exception;
}
