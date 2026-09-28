package qulla;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.Enums;
import com.motivewave.platform.sdk.common.NVP;
import com.motivewave.platform.sdk.common.PathInfo;
import com.motivewave.platform.sdk.common.Util;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.DiscreteDescriptor;
import com.motivewave.platform.sdk.common.desc.DoubleDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.draw.Marker;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

/**
 * Qullamaggie-inspired NQ strategy (MotiveWave auto-entry).
 * Setups: Breakout (long), Gap Pivot (long EP adaptation), Parabolic Short.
 * Designed for 5-minute NQ charts during RTH.
 */
@StudyHeader(
    namespace = "com.qulla.nq",
    id = "QULLA_NQ",
    name = "Qulla NQ Strategy",
    label = "Qulla NQ",
    desc = "NQ adaptation of Qullamaggie Breakout / Gap Pivot / Parabolic Short. Activate for auto-entry.",
    menu = "Qulla",
    overlay = true,
    studyOverlay = true,
    strategy = true,
    autoEntry = true,
    requiresVolume = true,
    supportsBarUpdates = true,
    supportsSessions = true,
    supportsEnterOnActivate = true,
    supportsCloseOnDeactivate = true,
    supportsLongShort = true,
    supportsPosition = true,
    supportsUnrealizedPL = true,
    supportsRealizedPL = true,
    showTradeOptions = true)
public class QullaNqStrategy extends Study
{
  static final String ENABLE_BREAKOUT = "enableBreakout";
  static final String ENABLE_GAP = "enableGap";
  static final String ENABLE_PARA = "enablePara";

  static final String IMPULSE_ATR = "impulseAtr";
  static final String IMPULSE_LOOKBACK = "impulseLookback";
  static final String CONSOL_BARS = "consolBars";
  static final String OR_BARS = "orBars";

  static final String GAP_ATR = "gapAtr";
  static final String GAP_POINTS = "gapPoints";
  static final String VOL_MULT = "volMult";
  static final String VOL_AVG_BARS = "volAvgBars";

  static final String STRETCH_ATR = "stretchAtr";
  static final String STRETCH_BARS = "stretchBars";
  static final String CONSEC_GREEN = "consecGreen";
  static final String PARA_ENTRY = "paraEntry";

  static final String STOP_ATR = "stopAtr";
  static final String SCALE_PCT = "scalePct";
  static final String SCALE_R = "scaleR";
  static final String TRAIL_MA = "trailMa";
  static final String CONTRACTS = "contracts";

  static final String SHOW_HUD = "showHud";
  static final String SHOW_LEVELS = "showLevels";
  static final String SHOW_MARKERS = "showMarkers";

  static final String EMA10_PATH = "ema10Path";
  static final String EMA20_PATH = "ema20Path";
  static final String ORH_PATH = "orhPath";
  static final String ORL_PATH = "orlPath";
  static final String STOP_PATH = "stopPath";

  enum Values { EMA10, EMA20, ATR, VWAP, ORH, ORL, CONSOL_HIGH, CONSOL_LOW, SETUP, SIGNAL }

  /** Setup codes stored in series: 0=none 1=breakout 2=gap 3=para */
  static final int SETUP_NONE = 0;
  static final int SETUP_BREAKOUT = 1;
  static final int SETUP_GAP = 2;
  static final int SETUP_PARA = 3;

  static final ZoneId NY = ZoneId.of("America/New_York");
  static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
  static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

  static final Color C_EMA10 = new Color(52, 152, 219);
  static final Color C_EMA20 = new Color(155, 89, 182);
  static final Color C_OR = new Color(241, 196, 15);
  static final Color C_STOP = new Color(231, 76, 60);
  static final Color C_HUD = new Color(20, 22, 28);
  static final Color C_LONG = new Color(25, 120, 65);
  static final Color C_SHORT = new Color(170, 35, 35);
  /** Bump when shipping HUD/logic changes so the chart shows the loaded build. */
  static final String BUILD = "v2026-09-28c";

  private static class TradeMark
  {
    final long time;
    final double price;
    final boolean isLong;
    final boolean isEntry;
    final String label;

    TradeMark(long time, double price, boolean isLong, boolean isEntry, String label)
    {
      this.time = time;
      this.price = price;
      this.isLong = isLong;
      this.isEntry = isEntry;
      this.label = label;
    }
  }

  private final List<TradeMark> tradeMarks = new ArrayList<>();
  private volatile long lastSignalTime;

  private volatile String activeSetup = "";
  private volatile double entryPrice;
  private volatile double stopPrice;
  private volatile double riskPerUnit;
  private volatile boolean scaled;
  private volatile int entryQty;
  private volatile LocalDate sessionDate;
  private volatile double sessionHigh = Double.NaN;
  private volatile double sessionLow = Double.NaN;
  private volatile double orHigh = Double.NaN;
  private volatile double orLow = Double.NaN;
  private volatile int orBarsSeen;
  private volatile boolean gapCandidate;
  private volatile boolean stretchCandidate;
  private volatile boolean breakoutReady;
  private volatile double consolHigh = Double.NaN;
  private volatile double consolLow = Double.NaN;
  private volatile double prevRthClose = Double.NaN;
  private volatile LocalDate lastProcessedRthDate;
  private volatile String hudLine1 = "Qulla NQ idle";
  private volatile String hudLine2 = "";
  private volatile String hudLine3 = "";
  private volatile String hudLine4 = "";
  private volatile Color hudBadgeColor = C_HUD;
  private volatile boolean strategyArmed;

  @Override
  public void initialize(Defaults defaults)
  {
    var sd = createSD();
    var tab = sd.addTab("Setups");
    var grp = tab.addGroup("Enable");
    grp.addRow(new BooleanDescriptor(ENABLE_BREAKOUT, "Breakout (long)", true));
    grp.addRow(new BooleanDescriptor(ENABLE_GAP, "Gap Pivot (long)", true));
    grp.addRow(new BooleanDescriptor(ENABLE_PARA, "Parabolic Short", true));

    grp = tab.addGroup("Breakout");
    grp.addRow(new DoubleDescriptor(IMPULSE_ATR, "Impulse ATR Mult", 3.0, 0.5, 20.0, 0.1));
    grp.addRow(new IntegerDescriptor(IMPULSE_LOOKBACK, "Impulse Lookback (bars)", 78, 10, 500, 1));
    grp.addRow(new IntegerDescriptor(CONSOL_BARS, "Consolidation Bars", 24, 5, 200, 1));
    grp.addRow(new IntegerDescriptor(OR_BARS, "Opening Range Bars", 3, 1, 30, 1));

    tab = sd.addTab("Gap / Para");
    grp = tab.addGroup("Gap Pivot");
    grp.addRow(new DoubleDescriptor(GAP_ATR, "Gap ATR Mult", 0.5, 0.1, 10.0, 0.1));
    grp.addRow(new DoubleDescriptor(GAP_POINTS, "Gap Min Points", 40.0, 0.0, 500.0, 1.0));
    grp.addRow(new DoubleDescriptor(VOL_MULT, "Open Volume Mult", 1.5, 0.5, 10.0, 0.1));
    grp.addRow(new IntegerDescriptor(VOL_AVG_BARS, "Avg Volume Bars", 20, 5, 100, 1));

    grp = tab.addGroup("Parabolic Short");
    grp.addRow(new DoubleDescriptor(STRETCH_ATR, "Stretch ATR Mult", 4.0, 0.5, 30.0, 0.1));
    grp.addRow(new IntegerDescriptor(STRETCH_BARS, "Stretch Lookback (bars)", 78, 10, 500, 1));
    grp.addRow(new IntegerDescriptor(CONSEC_GREEN, "Consec Green Days", 3, 2, 10, 1));
    grp.addRow(new DiscreteDescriptor(PARA_ENTRY, "Entry Mode", "VWAP_FAIL", Arrays.asList(
        new NVP("ORL Break", "ORL"),
        new NVP("VWAP Fail", "VWAP_FAIL"))));

    tab = sd.addTab("Risk / Display");
    grp = tab.addGroup("Exits & Size");
    grp.addRow(new DoubleDescriptor(STOP_ATR, "Max Stop ATR Mult", 1.5, 0.2, 5.0, 0.1));
    grp.addRow(new IntegerDescriptor(SCALE_PCT, "Scale Out %", 50, 0, 90, 5));
    grp.addRow(new DoubleDescriptor(SCALE_R, "Scale at R Multiple", 1.5, 0.5, 10.0, 0.1));
    grp.addRow(new DiscreteDescriptor(TRAIL_MA, "Trail MA", "10", Arrays.asList(
        new NVP("EMA 10", "10"),
        new NVP("EMA 20", "20"))));
    grp.addRow(new IntegerDescriptor(CONTRACTS, "Contracts", 1, 1, 50, 1));

    grp = tab.addGroup("Display");
    grp.addRow(new BooleanDescriptor(SHOW_HUD, "Show HUD", true));
    grp.addRow(new BooleanDescriptor(SHOW_LEVELS, "Show Levels", true));
    grp.addRow(new BooleanDescriptor(SHOW_MARKERS, "Show Entry/Exit Markers", true));
    grp.addRow(new PathDescriptor(EMA10_PATH, "EMA 10", C_EMA10, 1.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(EMA20_PATH, "EMA 20", C_EMA20, 1.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(ORH_PATH, "Opening Range High", C_OR, 1.0f, new float[] { 4f, 4f }, true, false, true));
    grp.addRow(new PathDescriptor(ORL_PATH, "Opening Range Low", C_OR, 1.0f, new float[] { 4f, 4f }, true, false, true));
    grp.addRow(new PathDescriptor(STOP_PATH, "Stop Level", C_STOP, 1.0f, new float[] { 6f, 4f }, true, false, true));

    var rd = createRD();
    rd.declarePath(Values.EMA10, EMA10_PATH);
    rd.declarePath(Values.EMA20, EMA20_PATH);
    rd.declarePath(Values.ORH, ORH_PATH);
    rd.declarePath(Values.ORL, ORL_PATH);
    rd.setLabelPrefix("Qulla");
  }

  @Override
  public void onLoad(Defaults defaults)
  {
    resetSessionState();
    activeSetup = "";
    scaled = false;
    strategyArmed = false;
    tradeMarks.clear();
    hudBadgeColor = C_HUD;
    hudLine1 = "OFF  Qulla NQ loaded  [" + BUILD + "]";
    hudLine2 = "Activate to arm — entries only RTH 9:30-16:00 ET";
    hudLine3 = "Setups: Breakout / Gap Pivot / Parabolic Short";
    hudLine4 = "Green triangle = long entry | Red = short | Diamond = exit";
  }

  @Override
  protected void calculate(int index, DataContext ctx)
  {
    DataSeries series = ctx.getDataSeries();
    if (series == null || index < 25) return;

    Double e10 = series.ema(index, 10, Enums.BarInput.CLOSE);
    Double e20 = series.ema(index, 20, Enums.BarInput.CLOSE);
    Double atrVal = series.atr(index, 14);
    if (e10 != null) series.setDouble(index, Values.EMA10, e10);
    if (e20 != null) series.setDouble(index, Values.EMA20, e20);
    if (atrVal != null) series.setDouble(index, Values.ATR, atrVal);

    updateSessionMetrics(index, series);
    computeVwap(index, series);
    detectSetups(index, series);

    Double orh = Double.isNaN(orHigh) ? null : orHigh;
    Double orl = Double.isNaN(orLow) ? null : orLow;
    if (orh != null) series.setDouble(index, Values.ORH, orh);
    if (orl != null) series.setDouble(index, Values.ORL, orl);
    if (!Double.isNaN(consolHigh)) series.setDouble(index, Values.CONSOL_HIGH, consolHigh);
    if (!Double.isNaN(consolLow)) series.setDouble(index, Values.CONSOL_LOW, consolLow);

    series.setComplete(index);
    if (index >= series.size() - 2) {
      refreshHudStatus(series, index, 0);
    }
  }

  @Override
  public void onActivate(OrderContext ctx)
  {
    strategyArmed = true;
    DataContext dc = ctx.getDataContext();
    if (dc != null && dc.getDataSeries() != null) {
      DataSeries s = dc.getDataSeries();
      int idx = Math.max(0, s.size() - 1);
      refreshHudStatus(s, idx, ctx.getPosition());
      drawHud(dc);
    }
    else {
      hudBadgeColor = C_LONG;
      hudLine1 = "ARMED  Waiting for bars";
      hudLine2 = "Entries only during RTH 9:30-16:00 ET";
      hudLine3 = "Press nothing else — strategy is scanning when RTH opens";
      hudLine4 = "";
      notifyRedraw();
    }
  }

  @Override
  public void onDeactivate(OrderContext ctx)
  {
    strategyArmed = false;
    hudBadgeColor = C_HUD;
    hudLine1 = "OFF  Strategy deactivated";
    hudLine2 = "No new entries from Qulla";
    hudLine3 = "";
    hudLine4 = "";
    DataContext dc = ctx.getDataContext();
    if (dc != null) drawHud(dc);
    else notifyRedraw();
  }

  @Override
  public void onBarUpdate(DataContext ctx)
  {
    DataSeries series = ctx.getDataSeries();
    if (series == null || series.size() < 30) return;
    int index = series.size() - 1;
    refreshHudStatus(series, index, 0);
    drawHud(ctx);
  }

  @Override
  public void onBarClose(OrderContext ctx)
  {
    DataContext dc = ctx.getDataContext();
    if (dc == null) return;
    DataSeries series = dc.getDataSeries();
    if (series == null || series.size() < 30) return;

    int index = series.size() - 1;
    if (!series.isBarComplete(index)) index--;
    if (index < 25) return;

    ZonedDateTime barZ = zdt(series.getStartTime(index));
    if (isRth(barZ)) {
      manageOpenPosition(ctx, series, index);
      if (ctx.getPosition() == 0) {
        tryEnter(ctx, series, index);
      }
    }
    refreshHudStatus(series, index, ctx.getPosition());
    drawHud(dc);
  }

  @Override
  public void onPositionClosed(OrderContext ctx)
  {
    activeSetup = "";
    scaled = false;
    stopPrice = Double.NaN;
    riskPerUnit = 0;
    entryPrice = 0;
    entryQty = 0;
    DataContext dc = ctx.getDataContext();
    if (dc != null && dc.getDataSeries() != null) {
      DataSeries s = dc.getDataSeries();
      refreshHudStatus(s, Math.max(0, s.size() - 1), 0);
      drawHud(dc);
    }
  }

  /**
   * 4-line guide:
   * L1 mode, L2 setup readiness, L3 next action, L4 levels.
   */
  private void refreshHudStatus(DataSeries series, int index, int position)
  {
    if (series == null || index < 0 || index >= series.size()) return;

    ZonedDateTime now = ZonedDateTime.now(NY);
    ZonedDateTime barZ = zdt(series.getStartTime(index));
    boolean rthNow = isRth(now);
    boolean rthBar = isRth(barZ);
    double close = series.getClose(index);
    Double atr = series.getDouble(index, Values.ATR);
    Double vwap = series.getDouble(index, Values.VWAP);
    int orN = getSettings().getInteger(OR_BARS, 3);

    if (!strategyArmed) {
      hudBadgeColor = C_HUD;
      hudLine1 = "OFF  Activate to arm entries  [" + BUILD + "]";
    }
    else if (position != 0) {
      hudBadgeColor = position > 0 ? C_LONG : C_SHORT;
      String side = position > 0 ? "LONG" : "SHORT";
      hudLine1 = "IN TRADE  " + side + " x" + Math.abs(position)
          + (Util.isEmpty(activeSetup) ? "" : ("  [" + activeSetup + "]"))
          + "  [" + BUILD + "]";
    }
    else if (!rthNow) {
      hudBadgeColor = new Color(120, 90, 30);
      String until = minutesUntilRth(now);
      hudLine1 = "ARMED  Outside RTH — no entries"
          + (Util.isEmpty(until) ? "" : ("  |  RTH in " + until))
          + "  [" + BUILD + "]";
    }
    else {
      hudBadgeColor = C_LONG;
      hudLine1 = "ARMED  RTH live — scanning setups  [" + BUILD + "]";
    }

    StringBuilder setups = new StringBuilder("Setups: ");
    boolean any = false;
    if (getSettings().getBoolean(ENABLE_BREAKOUT, true)) {
      setups.append(breakoutReady ? "BREAKOUT✓  " : "Breakout…  ");
      any = any || breakoutReady;
    }
    if (getSettings().getBoolean(ENABLE_GAP, true)) {
      setups.append(gapCandidate ? "GAP✓  " : "Gap…  ");
      any = any || gapCandidate;
    }
    if (getSettings().getBoolean(ENABLE_PARA, true)) {
      setups.append(stretchCandidate ? "PARA✓  " : "Para…  ");
      any = any || stretchCandidate;
    }
    if (!any && strategyArmed && rthNow && position == 0) {
      setups.append("| none ready yet");
    }
    hudLine2 = setups.toString().trim();

    if (position != 0) {
      boolean useEma10 = "10".equals(getSettings().getString(TRAIL_MA));
      Double trail = useEma10 ? series.getDouble(index, Values.EMA10) : series.getDouble(index, Values.EMA20);
      String trailName = useEma10 ? "EMA10" : "EMA20";
      if (position > 0) {
        double rNow = riskPerUnit > 0 ? (close - entryPrice) / riskPerUnit : 0;
        hudLine3 = scaled
            ? ("Manage: trail " + trailName + " @" + fmt(trail) + "  |  stop " + fmt(stopPrice)
                + "  |  " + String.format("%+.1fR", rNow))
            : ("Next: scale " + getSettings().getInteger(SCALE_PCT, 50) + "% at +"
                + getSettings().getDouble(SCALE_R, 1.5) + "R ("
                + fmt(entryPrice + getSettings().getDouble(SCALE_R, 1.5) * riskPerUnit)
                + ")  |  stop " + fmt(stopPrice));
      }
      else {
        double rNow = riskPerUnit > 0 ? (entryPrice - close) / riskPerUnit : 0;
        hudLine3 = scaled
            ? ("Manage: cover above " + trailName + " @" + fmt(trail) + "  |  stop " + fmt(stopPrice))
            : ("Next: scale " + getSettings().getInteger(SCALE_PCT, 50) + "% at +"
                + getSettings().getDouble(SCALE_R, 1.5) + "R  |  stop " + fmt(stopPrice)
                + "  |  " + String.format("%+.1fR", rNow));
      }
    }
    else if (!strategyArmed) {
      hudLine3 = "Press Activate on the strategy panel to start";
    }
    else if (!rthNow) {
      hudLine3 = "Premarket/AH moves ignored — waiting for 9:30 ET cash open";
    }
    else if (orBarsSeen < orN) {
      hudLine3 = "Building Opening Range… " + orBarsSeen + "/" + orN
          + " bars (entries wait until OR is complete)";
    }
    else if (gapCandidate && getSettings().getBoolean(ENABLE_GAP, true)) {
      hudLine3 = "NEXT: GAP long if price breaks ORH " + fmt(orHigh) + "  (stop LOD capped by ATR)";
    }
    else if (breakoutReady && getSettings().getBoolean(ENABLE_BREAKOUT, true)) {
      hudLine3 = "NEXT: BREAKOUT long on break of consol H " + fmt(consolHigh)
          + (!Double.isNaN(orHigh) ? (" / watch ORH " + fmt(orHigh)) : "");
    }
    else if (stretchCandidate && getSettings().getBoolean(ENABLE_PARA, true)) {
      if ("ORL".equals(paraEntryMode())) {
        hudLine3 = "NEXT: PARA short if price breaks ORL " + fmt(orLow);
      }
      else {
        hudLine3 = "NEXT: PARA short on VWAP fail (into VWAP then close below "
            + fmt(vwap) + ")";
      }
    }
    else {
      hudLine3 = "Waiting: impulse+tight consol (BO), overnight gap+ORH (GAP), or stretch (PARA)";
    }

    StringBuilder lv = new StringBuilder();
    lv.append("Spot ").append(fmt(close));
    if (atr != null) lv.append("  ATR ").append(fmt(atr));
    if (!Double.isNaN(orHigh)) {
      lv.append("  |  ORH ").append(fmt(orHigh)).append(" (").append(signedDist(close, orHigh)).append(")");
    }
    if (!Double.isNaN(orLow)) {
      lv.append("  ORL ").append(fmt(orLow)).append(" (").append(signedDist(close, orLow)).append(")");
    }
    if (vwap != null) lv.append("  VWAP ").append(fmt(vwap));
    if (breakoutReady && !Double.isNaN(consolHigh)) lv.append("  ConsolH ").append(fmt(consolHigh));
    if (gapCandidate) lv.append("  Gap✓");
    if (stretchCandidate) lv.append("  Stretch✓");
    if (!rthBar && rthNow) lv.append("  [bar catching up]");
    hudLine4 = lv.toString();
  }

  private static String minutesUntilRth(ZonedDateTime now)
  {
    if (isRth(now)) return "";
    ZonedDateTime next;
    if (now.getDayOfWeek().getValue() > 5 || !now.toLocalTime().isBefore(RTH_CLOSE)) {
      LocalDate d = now.toLocalDate().plusDays(now.getDayOfWeek().getValue() > 5 ? 0 : 1);
      while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
      if (now.getDayOfWeek().getValue() > 5) {
        d = now.toLocalDate();
        while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
      }
      next = d.atTime(RTH_OPEN).atZone(NY);
      if (!next.isAfter(now)) {
        d = d.plusDays(1);
        while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
        next = d.atTime(RTH_OPEN).atZone(NY);
      }
    }
    else {
      next = now.toLocalDate().atTime(RTH_OPEN).atZone(NY);
    }
    long mins = java.time.Duration.between(now, next).toMinutes();
    if (mins < 0) return "";
    if (mins >= 60) return (mins / 60) + "h" + String.format("%02d", mins % 60) + "m";
    return mins + "m";
  }

  private static String signedDist(double spot, double level)
  {
    double d = spot - level;
    return (d >= 0 ? "+" : "") + String.format("%.0f", d);
  }

  private static String fmt(Double v)
  {
    if (v == null || Double.isNaN(v)) return "-";
    return fmt(v.doubleValue());
  }

  // ---------- Session / indicators ----------

  private void resetSessionState()
  {
    sessionDate = null;
    sessionHigh = Double.NaN;
    sessionLow = Double.NaN;
    orHigh = Double.NaN;
    orLow = Double.NaN;
    orBarsSeen = 0;
    gapCandidate = false;
    stretchCandidate = false;
    breakoutReady = false;
    consolHigh = Double.NaN;
    consolLow = Double.NaN;
  }

  private void updateSessionMetrics(int index, DataSeries series)
  {
    ZonedDateTime z = zdt(series.getStartTime(index));
    LocalDate day = z.toLocalDate();
    boolean rth = isRth(z);

    if (sessionDate == null || !sessionDate.equals(day)) {
      // Capture previous RTH close before resetting
      if (sessionDate != null && !Double.isNaN(sessionHigh)) {
        // previous bar close of prior session handled via last close while in RTH
      }
      if (rth && (lastProcessedRthDate == null || !lastProcessedRthDate.equals(day))) {
        // New RTH day: compute gap vs prevRthClose
        double open = series.getOpen(index);
        Double atr = series.getDouble(index, Values.ATR);
        if (!Double.isNaN(prevRthClose) && atr != null && atr > 0) {
          double gapPts = open - prevRthClose;
          double gapAtr = getSettings().getDouble(GAP_ATR, 0.5);
          double gapMin = getSettings().getDouble(GAP_POINTS, 40.0);
          gapCandidate = gapPts >= Math.max(gapMin, gapAtr * atr);
        }
        else {
          gapCandidate = false;
        }
        lastProcessedRthDate = day;
      }

      sessionDate = day;
      orHigh = Double.NaN;
      orLow = Double.NaN;
      orBarsSeen = 0;
      sessionHigh = Double.NaN;
      sessionLow = Double.NaN;
      breakoutReady = false;
    }

    if (!rth) return;

    double high = series.getHigh(index);
    double low = series.getLow(index);
    double close = series.getClose(index);
    prevRthClose = close;

    sessionHigh = Double.isNaN(sessionHigh) ? high : Math.max(sessionHigh, high);
    sessionLow = Double.isNaN(sessionLow) ? low : Math.min(sessionLow, low);

    int orN = getSettings().getInteger(OR_BARS, 3);
    if (orBarsSeen < orN) {
      orHigh = Double.isNaN(orHigh) ? high : Math.max(orHigh, high);
      orLow = Double.isNaN(orLow) ? low : Math.min(orLow, low);
      orBarsSeen++;
    }
  }

  private void computeVwap(int index, DataSeries series)
  {
    ZonedDateTime z = zdt(series.getStartTime(index));
    if (!isRth(z)) {
      series.setDouble(index, Values.VWAP, (double) series.getClose(index));
      return;
    }

    double pv = 0;
    double vol = 0;
    LocalDate day = z.toLocalDate();
    for (int i = index; i >= 0; i--) {
      ZonedDateTime zi = zdt(series.getStartTime(i));
      if (!zi.toLocalDate().equals(day) || !isRth(zi)) break;
      double tp = (series.getHigh(i) + series.getLow(i) + series.getClose(i)) / 3.0;
      double v = series.getVolumeAsFloat(i);
      if (v <= 0) v = 1;
      pv += tp * v;
      vol += v;
    }
    series.setDouble(index, Values.VWAP, vol > 0 ? pv / vol : (double) series.getClose(index));
  }

  // ---------- Detection ----------

  private void detectSetups(int index, DataSeries series)
  {
    int setup = SETUP_NONE;
    Double atr = series.getDouble(index, Values.ATR);
    Double ema10 = series.getDouble(index, Values.EMA10);
    Double ema20 = series.getDouble(index, Values.EMA20);
    if (atr == null || atr <= 0 || ema10 == null || ema20 == null) {
      series.setInt(index, Values.SETUP, SETUP_NONE);
      return;
    }

    ZonedDateTime z = zdt(series.getStartTime(index));
    if (!isRth(z)) {
      series.setInt(index, Values.SETUP, SETUP_NONE);
      return;
    }

    // Breakout readiness (structure)
    if (getSettings().getBoolean(ENABLE_BREAKOUT, true)) {
      breakoutReady = isBreakoutStructure(index, series, atr, ema10, ema20);
      if (breakoutReady) setup = SETUP_BREAKOUT;
    }

    // Gap pivot candidate stays for the session once volume confirms
    if (getSettings().getBoolean(ENABLE_GAP, true) && gapCandidate && volumeConfirms(index, series)) {
      setup = SETUP_GAP;
    }

    // Parabolic stretch
    if (getSettings().getBoolean(ENABLE_PARA, true)) {
      stretchCandidate = isStretch(index, series, atr);
      if (stretchCandidate) setup = SETUP_PARA;
    }

    series.setInt(index, Values.SETUP, setup);
  }

  private boolean isBreakoutStructure(int index, DataSeries series, double atr, double ema10, double ema20)
  {
    int lookback = getSettings().getInteger(IMPULSE_LOOKBACK, 78);
    int consol = getSettings().getInteger(CONSOL_BARS, 24);
    double impulseMult = getSettings().getDouble(IMPULSE_ATR, 3.0);
    if (index < lookback + consol) return false;

    // Impulse: large range in lookback window before consolidation
    int impulseEnd = index - consol;
    int impulseStart = Math.max(0, impulseEnd - lookback);
    double iHigh = series.getHigh(impulseStart);
    double iLow = series.getLow(impulseStart);
    for (int i = impulseStart; i <= impulseEnd; i++) {
      iHigh = Math.max(iHigh, series.getHigh(i));
      iLow = Math.min(iLow, series.getLow(i));
    }
    if ((iHigh - iLow) < impulseMult * atr) return false;

    // Consolidation: tight range, above EMAs, higher lows
    double cHigh = series.getHigh(index - consol + 1);
    double cLow = series.getLow(index - consol + 1);
    double prevLow = cLow;
    boolean higherLows = true;
    for (int i = index - consol + 1; i <= index; i++) {
      cHigh = Math.max(cHigh, series.getHigh(i));
      cLow = Math.min(cLow, series.getLow(i));
      double low = series.getLow(i);
      if (low + atr * 0.05 < prevLow) higherLows = false;
      prevLow = Math.max(prevLow, low);
    }
    consolHigh = cHigh;
    consolLow = cLow;

    if ((cHigh - cLow) > atr * 2.5) return false;
    if (series.getClose(index) < ema10 || series.getClose(index) < ema20) return false;
    // Prefer rising MAs
    Double ema10Prev = series.getDouble(index - consol, Values.EMA10);
    if (ema10Prev != null && ema10 < ema10Prev) return false;
    return higherLows || (cHigh - cLow) <= atr * 1.5;
  }

  private boolean volumeConfirms(int index, DataSeries series)
  {
    int avgN = getSettings().getInteger(VOL_AVG_BARS, 20);
    double mult = getSettings().getDouble(VOL_MULT, 1.5);
    if (index < avgN) return false;
    double avg = 0;
    for (int i = index - avgN; i < index; i++) avg += series.getVolumeAsFloat(i);
    avg /= avgN;
    if (avg <= 0) return true;
    // Confirm on early session bars or current surge
    return series.getVolumeAsFloat(index) >= avg * mult || orBarsSeen <= getSettings().getInteger(OR_BARS, 3) + 2;
  }

  private boolean isStretch(int index, DataSeries series, double atr)
  {
    int lookback = getSettings().getInteger(STRETCH_BARS, 78);
    double stretchMult = getSettings().getDouble(STRETCH_ATR, 4.0);
    if (index < lookback) return false;

    double low = series.getLow(index - lookback);
    for (int i = index - lookback; i <= index; i++) low = Math.min(low, series.getLow(i));
    double move = series.getClose(index) - low;
    if (move >= stretchMult * atr) return true;

    // Consecutive green RTH days (approximate via day buckets)
    int need = getSettings().getInteger(CONSEC_GREEN, 3);
    int greenDays = 0;
    LocalDate last = null;
    double dayOpen = Double.NaN;
    double dayClose = Double.NaN;
    for (int i = index; i >= 0 && greenDays < need; i--) {
      ZonedDateTime zi = zdt(series.getStartTime(i));
      if (!isRth(zi)) continue;
      LocalDate d = zi.toLocalDate();
      if (last == null) {
        last = d;
        dayOpen = series.getOpen(i);
        dayClose = series.getClose(i);
      }
      else if (!d.equals(last)) {
        if (!Double.isNaN(dayClose) && !Double.isNaN(dayOpen) && dayClose > dayOpen) greenDays++;
        else break;
        last = d;
        dayOpen = series.getOpen(i);
        dayClose = series.getClose(i);
      }
      else {
        dayOpen = series.getOpen(i);
      }
    }
    if (!Double.isNaN(dayClose) && !Double.isNaN(dayOpen) && dayClose > dayOpen) greenDays++;
    return greenDays >= need;
  }

  // ---------- Entries / exits ----------

  private void tryEnter(OrderContext ctx, DataSeries series, int index)
  {
    Double atr = series.getDouble(index, Values.ATR);
    if (atr == null || atr <= 0) return;
    double close = series.getClose(index);
    double high = series.getHigh(index);
    double low = series.getLow(index);
    int qty = Math.max(1, getSettings().getInteger(CONTRACTS, 1));
    double maxStop = getSettings().getDouble(STOP_ATR, 1.5) * atr;

    // Priority: Gap > Breakout > Parabolic (long bias first)
    if (getSettings().getBoolean(ENABLE_GAP, true) && gapCandidate && volumeConfirms(index, series)
        && !Double.isNaN(orHigh) && orBarsSeen >= getSettings().getInteger(OR_BARS, 3)
        && high >= orHigh && close >= orHigh) {
      double stop = sessionLow;
      if (Double.isNaN(stop) || (close - stop) > maxStop || (close - stop) <= 0) stop = close - maxStop;
      enterLong(ctx, "GAP", close, stop, qty, series.getStartTime(index));
      return;
    }

    if (getSettings().getBoolean(ENABLE_BREAKOUT, true) && breakoutReady
        && !Double.isNaN(consolHigh) && high >= consolHigh && close >= consolHigh) {
      double stop = !Double.isNaN(consolLow) ? consolLow : sessionLow;
      if (Double.isNaN(stop) || (close - stop) > maxStop || (close - stop) <= 0) stop = close - maxStop;
      // Prefer ORH break confirmation if OR complete
      if (!Double.isNaN(orHigh) && orBarsSeen >= getSettings().getInteger(OR_BARS, 3) && close < orHigh
          && consolHigh < orHigh) {
        // still allow consol break alone
      }
      enterLong(ctx, "BREAKOUT", close, stop, qty, series.getStartTime(index));
      return;
    }

    if (getSettings().getBoolean(ENABLE_PARA, true) && stretchCandidate) {
      boolean trigger = false;
      String mode = paraEntryMode();
      Double vwap = series.getDouble(index, Values.VWAP);
      if ("ORL".equals(mode)) {
        trigger = !Double.isNaN(orLow) && orBarsSeen >= getSettings().getInteger(OR_BARS, 3)
            && low <= orLow && close <= orLow;
      }
      else if (vwap != null) {
        // Bounce into VWAP then fail: previous close above/near VWAP, now close below
        Double prevClose = index > 0 ? Double.valueOf(series.getClose(index - 1)) : null;
        Double prevVwap = index > 0 ? series.getDouble(index - 1, Values.VWAP) : null;
        boolean approached = prevClose != null && prevVwap != null && prevClose >= prevVwap * 0.999;
        trigger = approached && close < vwap && series.getClose(index) < series.getOpen(index);
      }
      if (trigger) {
        double stop = sessionHigh;
        if (Double.isNaN(stop) || (stop - close) > maxStop || (stop - close) <= 0) stop = close + maxStop;
        enterShort(ctx, "PARA", close, stop, qty, series.getStartTime(index));
      }
    }
  }

  private void enterLong(OrderContext ctx, String setup, double entry, double stop, int qty, long barTime)
  {
    try {
      ctx.buy(qty);
      activeSetup = setup;
      entryPrice = entry;
      stopPrice = stop;
      riskPerUnit = Math.max(0.25, entry - stop);
      scaled = false;
      entryQty = qty;
      lastSignalTime = barTime;
      addTradeMark(barTime, entry, true, true, "IN " + setup);
      debug("Qulla enter LONG " + setup + " @ " + entry + " stop " + stop);
    }
    catch (Exception ex) {
      hudLine1 = "ERR entry long";
      hudLine2 = safe(ex.getMessage());
      debug("Qulla long entry failed: " + ex.getMessage());
    }
  }

  private void enterShort(OrderContext ctx, String setup, double entry, double stop, int qty, long barTime)
  {
    try {
      ctx.sell(qty);
      activeSetup = setup;
      entryPrice = entry;
      stopPrice = stop;
      riskPerUnit = Math.max(0.25, stop - entry);
      scaled = false;
      entryQty = qty;
      lastSignalTime = barTime;
      addTradeMark(barTime, entry, false, true, "IN " + setup);
      debug("Qulla enter SHORT " + setup + " @ " + entry + " stop " + stop);
    }
    catch (Exception ex) {
      hudLine1 = "ERR entry short";
      hudLine2 = safe(ex.getMessage());
      debug("Qulla short entry failed: " + ex.getMessage());
    }
  }

  private void manageOpenPosition(OrderContext ctx, DataSeries series, int index)
  {
    int pos = ctx.getPosition();
    if (pos == 0) return;

    double close = series.getClose(index);
    long barTime = series.getStartTime(index);
    Double ema10 = series.getDouble(index, Values.EMA10);
    Double ema20 = series.getDouble(index, Values.EMA20);
    boolean useEma10 = "10".equals(getSettings().getString(TRAIL_MA));
    Double trail = useEma10 ? ema10 : ema20;
    double scaleR = getSettings().getDouble(SCALE_R, 1.5);
    int scalePct = getSettings().getInteger(SCALE_PCT, 50);

    if (pos > 0) {
      // Hard stop
      if (!Double.isNaN(stopPrice) && close <= stopPrice) {
        flatten(ctx, "STOP", barTime, close, true);
        return;
      }
      // Scale out
      if (!scaled && scalePct > 0 && riskPerUnit > 0 && close >= entryPrice + scaleR * riskPerUnit) {
        int exitQty = Math.max(1, (Math.abs(pos) * scalePct) / 100);
        exitQty = Math.min(exitQty, Math.abs(pos) - (Math.abs(pos) > 1 ? 1 : 0));
        if (exitQty > 0 && exitQty < Math.abs(pos)) {
          try {
            ctx.sell(exitQty);
            scaled = true;
            stopPrice = Math.max(stopPrice, entryPrice); // move toward BE
            addTradeMark(barTime, close, true, false, "SCALE");
            hudLine2 = "Scaled " + exitQty + " @ " + fmt(close) + "  BE stop";
          }
          catch (Exception ex) {
            debug("Qulla scale failed: " + ex.getMessage());
          }
        }
        else if (exitQty >= Math.abs(pos)) {
          // If only 1 contract, skip scale and let trail handle
          scaled = true;
        }
      }
      // Trail: first close below MA after scaled or always once in profit
      if (trail != null && close < trail && (scaled || close > entryPrice)) {
        flatten(ctx, "TRAIL", barTime, close, true);
      }
    }
    else { // short
      if (!Double.isNaN(stopPrice) && close >= stopPrice) {
        flatten(ctx, "STOP", barTime, close, false);
        return;
      }
      if (!scaled && scalePct > 0 && riskPerUnit > 0 && close <= entryPrice - scaleR * riskPerUnit) {
        int exitQty = Math.max(1, (Math.abs(pos) * scalePct) / 100);
        exitQty = Math.min(exitQty, Math.abs(pos) - (Math.abs(pos) > 1 ? 1 : 0));
        if (exitQty > 0 && exitQty < Math.abs(pos)) {
          try {
            ctx.buy(exitQty);
            scaled = true;
            stopPrice = Math.min(stopPrice, entryPrice);
            addTradeMark(barTime, close, false, false, "SCALE");
            hudLine2 = "Scaled " + exitQty + " @ " + fmt(close) + "  BE stop";
          }
          catch (Exception ex) {
            debug("Qulla scale failed: " + ex.getMessage());
          }
        }
        else {
          scaled = true;
        }
      }
      // Cover when price closes back above trail MA (mean reversion target zone)
      if (trail != null && close > trail && (scaled || close < entryPrice)) {
        flatten(ctx, "TRAIL", barTime, close, false);
      }
    }
  }

  private void flatten(OrderContext ctx, String reason, long barTime, double price, boolean wasLong)
  {
    try {
      ctx.closeAtMarket();
      addTradeMark(barTime, price, wasLong, false, "OUT " + reason);
      debug("Qulla flatten " + reason + " setup=" + activeSetup);
    }
    catch (Exception ex) {
      hudLine1 = "ERR flatten";
      hudLine2 = safe(ex.getMessage());
    }
  }

  private void addTradeMark(long time, double price, boolean isLong, boolean isEntry, String label)
  {
    synchronized (tradeMarks) {
      tradeMarks.add(new TradeMark(time, price, isLong, isEntry, label));
      // Keep chart readable
      while (tradeMarks.size() > 40) tradeMarks.remove(0);
    }
  }

  // ---------- Drawing ----------

  private void drawHud(DataContext ctx)
  {
    beginFigureUpdate();
    clearFigures();

    if (getSettings().getBoolean(SHOW_LEVELS, true)) {
      if (!Double.isNaN(orHigh)) addPriceLine(orHigh, C_OR, "ORH " + fmt(orHigh), true);
      if (!Double.isNaN(orLow)) addPriceLine(orLow, C_OR, "ORL " + fmt(orLow), true);
      if (!Double.isNaN(consolHigh) && breakoutReady)
        addPriceLine(consolHigh, new Color(46, 204, 113), "CONSOL H " + fmt(consolHigh), true);
      if (!Double.isNaN(stopPrice) && entryQty > 0)
        addPriceLine(stopPrice, C_STOP, "STOP " + fmt(stopPrice), true);
    }

    if (getSettings().getBoolean(SHOW_MARKERS, true)) {
      List<TradeMark> marks;
      synchronized (tradeMarks) {
        marks = new ArrayList<>(tradeMarks);
      }
      for (TradeMark m : marks) {
        Color fill = m.isEntry
            ? (m.isLong ? C_LONG : C_SHORT)
            : new Color(241, 196, 15);
        Enums.MarkerType type = m.isEntry ? Enums.MarkerType.TRIANGLE : Enums.MarkerType.DIAMOND;
        Enums.Position pos = m.isEntry
            ? (m.isLong ? Enums.Position.BOTTOM : Enums.Position.TOP)
            : Enums.Position.CENTER;
        Marker mk = new Marker(
            new Coordinate(m.time, m.price),
            type,
            Enums.Size.MEDIUM,
            pos,
            fill,
            Color.WHITE);
        mk.setTextValue(m.label);
        mk.setTextPosition(m.isLong ? Enums.Position.BOTTOM : Enums.Position.TOP);
        addFigure(mk);
      }
    }

    if (getSettings().getBoolean(SHOW_HUD, true)) {
      addFigure(new HudPanel(hudLine1, hudBadgeColor, Color.WHITE, 8, true));
      int y = 36;
      if (!Util.isEmpty(hudLine2)) {
        addFigure(new HudPanel(hudLine2, C_HUD, new Color(230, 230, 230), y, false));
        y += 28;
      }
      if (!Util.isEmpty(hudLine3)) {
        addFigure(new HudPanel(hudLine3, new Color(28, 32, 42), new Color(255, 220, 120), y, false));
        y += 28;
      }
      if (!Util.isEmpty(hudLine4)) {
        addFigure(new HudPanel(hudLine4, C_HUD, new Color(200, 205, 215), y, false));
      }
    }

    endFigureUpdate();
    notifyRedraw();
  }

  private void addPriceLine(double price, Color color, String label, boolean dashed)
  {
    addFigure(new LevelLine(price, color, 1.0f, label, dashed));
  }

  private static class LevelLine extends Figure
  {
    private final double price;
    private final Color color;
    private final float width;
    private final String label;
    private final boolean dashed;
    private Line2D.Double line;

    LevelLine(double price, Color color, float width, String label, boolean dashed)
    {
      this.price = price;
      this.color = color;
      this.width = width;
      this.label = label;
      this.dashed = dashed;
    }

    @Override
    public boolean isVisible(DrawContext ctx) { return true; }

    @Override
    public void layout(DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      double y = ctx.translateValueD(price);
      line = new Line2D.Double(gb.getX(), y, gb.getMaxX(), y);
      setBounds(new Rectangle2D.Double(gb.getX(), y - 14, gb.getWidth(), 18));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      if (line == null) return;
      if (dashed) {
        gc.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] { 5f, 4f }, 0f));
      }
      else {
        gc.setStroke(new BasicStroke(width));
      }
      gc.setColor(color);
      gc.draw(line);
      if (label != null) {
        Font font = new Font("SansSerif", Font.BOLD, 11);
        gc.setFont(font);
        FontMetrics fm = gc.getFontMetrics(font);
        int tw = fm.stringWidth(label);
        Rectangle gb = ctx.getBounds();
        gc.setColor(color);
        gc.drawString(label, (int) gb.getMaxX() - tw - 10, (int) Math.round(line.getY1()) - 4);
      }
    }
  }

  private static class HudPanel extends Figure
  {
    private final String text;
    private final Color bg;
    private final Color fg;
    private final int yOff;
    private final boolean bold;

    HudPanel(String text, Color bg, Color fg, int yOff, boolean bold)
    {
      this.text = text;
      this.bg = bg;
      this.fg = fg;
      this.yOff = yOff;
      this.bold = bold;
    }

    @Override
    public boolean isVisible(DrawContext ctx) { return true; }

    @Override
    public void layout(DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      int w = (text == null ? 40 : text.length() * 7) + 24;
      int bx = (int) gb.getMaxX() - w - 8;
      setBounds(new Rectangle2D.Double(bx, gb.getY() + yOff, w, 24));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      if (Util.isEmpty(text)) return;
      Rectangle gb = ctx.getBounds();
      Font font = new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, bold ? 13 : 12);
      gc.setFont(font);
      FontMetrics fm = gc.getFontMetrics(font);
      int padX = 10, padY = 6;
      int tw = fm.stringWidth(text);
      int th = fm.getHeight();
      int boxW = tw + padX * 2;
      int boxH = th + padY;
      int bx = (int) gb.getMaxX() - boxW - 8;
      int by = (int) gb.getY() + yOff;
      Rectangle2D.Double box = new Rectangle2D.Double(bx, by, boxW, boxH);
      setBounds(box);
      gc.setColor(bg);
      gc.fill(box);
      gc.setColor(fg);
      gc.draw(box);
      gc.drawString(text, bx + padX, by + fm.getAscent() + padY / 2);
    }
  }

  // ---------- Helpers ----------

  private String paraEntryMode()
  {
    String m = getSettings().getString(PARA_ENTRY);
    return Util.isEmpty(m) ? "VWAP_FAIL" : m;
  }

  private static ZonedDateTime zdt(long millis)
  {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), NY);
  }

  private static boolean isRth(ZonedDateTime z)
  {
    LocalTime t = z.toLocalTime();
    return !t.isBefore(RTH_OPEN) && t.isBefore(RTH_CLOSE)
        && z.getDayOfWeek().getValue() <= 5;
  }

  private static String fmt(double v)
  {
    if (Math.abs(v - Math.rint(v)) < 0.05) return String.format("%.0f", v);
    return String.format("%.2f", v);
  }

  private static String safe(String s)
  {
    if (s == null) return "unknown";
    return s.length() > 80 ? s.substring(0, 80) : s;
  }
}
