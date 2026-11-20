package com.jb.rts.mesh;

/**
 * Metric length units used by the mesh exports and the world model.
 *
 * <p>World/mesh coordinates are stored in <b>metres</b> (m); this enum provides the conversions
 * to/from millimetres ({@code mm}), centimetres ({@code cm}), metres ({@code m}) and kilometres
 * ({@code km}) so callers (an export's {@code measurement} field, a HUD, a grid) can display and
 * convert values in the unit that is most readable for the current scale.</p>
 *
 * <pre>{@code
 * float widthM  = 5000f;                                    // internal unit is always metres
 * float widthKm = MetricUnit.KILOMETER.fromMeters(widthM);   // 5.0
 * float widthCm = MetricUnit.CENTIMETER.fromMeters(widthM);  // 500000.0
 * }</pre>
 */
public enum MetricUnit {

    /** 1 km = 1000 m. */
    KILOMETER("km", 1000f),
    /** 1 m — the base unit (all world coordinates are stored in metres). */
    METER("m", 1f),
    /** 1 cm = 0.01 m. */
    CENTIMETER("cm", 0.01f),
    /** 1 mm = 0.001 m. */
    MILLIMETER("mm", 0.001f);

    private final String symbol;
    private final float  metersPerUnit;

    MetricUnit(String symbol, float metersPerUnit) {
        this.symbol        = symbol;
        this.metersPerUnit = metersPerUnit;
    }

    /** Unit symbol, e.g. {@code "km"}, {@code "m"}, {@code "cm"}, {@code "mm"}. */
    public String symbol() {
        return symbol;
    }

    /** How many metres one unit of this metric is worth (e.g. {@code 1000} for km). */
    public float metersPerUnit() {
        return metersPerUnit;
    }

    /** Converts a value given in this unit to metres. */
    public float toMeters(float value) {
        return value * metersPerUnit;
    }

    /** Converts a value given in metres into this unit. */
    public float fromMeters(float meters) {
        return meters / metersPerUnit;
    }

    /** Converts {@code value} from this unit into {@code target}. */
    public float convert(float value, MetricUnit target) {
        return target.fromMeters(toMeters(value));
    }
}
