package trendtarget;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.DataSeries;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.Enums;
import com.motivewave.platform.sdk.common.Util;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.DoubleDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.draw.Marker;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

/**
 * Trend Target Ribbon — ALMA flip strategy with structure stop and R targets.
 * - ALMA trend + stdev bands + ATR-normalized slope → sticky bull/bear flips
 * - On flip: enter at close; structure stop (lookback) clamped to [minATR, maxATR]
 * - Targets at +1R…+NR (visual / MFE tracking)
 * - Position ends on SL hit OR opposite flip
 * Auto-backtest + HUD for net R / PF.
 */
@StudyHeader(
    namespace = "com.trendtarget.alma",
    id = "TTR_ALMA_STRATEGY",
    name = "Trend Target Ribbon",
    label = "TTR ALMA",
    desc = "ALMA flip + structure stop + R targets. Auto-backtest.",
    menu = "Trend Target",
    overlay = true,
    studyOverlay = true,
    strategy = true,
    autoEntry = true,
    supportsBarUpdates = true,
    supportsEnterOnActivate = true,
    supportsCloseOnDeactivate = true,
    supportsLongShort = true,
    supportsPosition = true,
    supportsUnrealizedPL = true,
    supportsRealizedPL = true,
    showTradeOptions = true)
public class TrendTargetRibbonStrategy extends Study
{
  static final String ALMA_LEN = "almaLen";
  static final String ALMA_OFFSET = "almaOffset";
  static final String ALMA_SIGMA = "almaSigma";
  static final String DEV_LEN = "devLen";
  static final String DEV_MULT = "devMult";
  static final String SLOPE_LEN = "slopeLen";
  static final String SLOPE_MIN = "slopeMin";
  static final String ATR_LEN = "atrLen";

  static final String STOP_LOOKBACK = "stopLookback";
  static final String MIN_STOP_ATR = "minStopAtr";
  static final String MAX_STOP_ATR = "maxStopAtr";
  static final String TARGET_COUNT = "targetCount";
  static final String KEEP_POSITIONS = "keepPositions";
  static final String ZONE_PCT = "zonePct";
  static final String EXTEND_BARS = "extendBars";
  static final String CONTRACTS = "contracts";
  static final String AUTO_BACKTEST = "autoBacktest";

  static final String SHOW_HUD = "showHud";
  static final String SHOW_MARKERS = "showMarkers";
  static final String SHOW_LEVELS = "showLevels";
  static final String SHOW_RIBBON = "showRibbon";
  static final String PAINT_BARS = "paintBars";

  static final String ALMA_PATH = "almaPath";
  static final String UPPER_PATH = "upperPath";
  static final String LOWER_PATH = "lowerPath";

  enum Values { ALMA, DEV, ATR, UPPER, LOWER, SLOPE, TREND, FLIP, EDGE, CONVICTION }

  static final int FLIP_NONE = 0;
  static final int FLIP_BULL = 1;
  static final int FLIP_BEAR = -1;

  static final Color C_BULL = new Color(0, 255, 0);
  static final Color C_BEAR = new Color(255, 0, 102);
  static final Color C_ALMA = new Color(255, 255, 255);
  static final Color C_SL = new Color(255, 0, 102);
  static final Color C_TP = new Color(0, 255, 0);
  static final Color C_ENTRY = new Color(255, 255, 255);
  static final Color C_HUD = new Color(20, 22, 28);
  static final Color C_PROFIT = new Color(20, 110, 55);
  static final Color C_LOSS = new Color(140, 30, 30);
  static final Color C_NEUTRAL = new Color(85, 85, 85);
  static final String BUILD = "v2026-09-30c";

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

  private static class ClosedTrade
  {
    final boolean isLong;
    final double entry;
    final double exit;
    final double risk;
    final double pnlR;
    final String reason;
    final int maxTargetHit;

    ClosedTrade(boolean isLong, double entry, double exit, double risk, String reason, int maxTargetHit)
    {
      this.isLong = isLong;
      this.entry = entry;
      this.exit = exit;
      this.risk = risk;
      this.reason = reason;
      this.maxTargetHit = maxTargetHit;
      this.pnlR = risk > 0
          ? (isLong ? (exit - entry) / risk : (entry - exit) / risk)
          : 0;
    }
  }

  private static class HudRow
  {
    final String label;
    final String value;
    final Color accent;
    final boolean section;

    private HudRow(String label, String value, Color accent, boolean section)
    {
      this.label = label;
      this.value = value;
      this.accent = accent;
      this.section = section;
    }

    static HudRow section(String title) { return new HudRow(title, "", null, true); }
    static HudRow kv(String label, String value) { return new HudRow(label, value, null, false); }
    static HudRow kv(String label, String value, Color accent) { return new HudRow(label, value, accent, false); }
  }

  /** Visual plan for one trade (matches Pine entry/SL/targets/risk box). */
  private static class PositionVisual
  {
    final long startTime;
    long endTime;
    final boolean isLong;
    final double entry;
    final double stop;
    final double risk;
    final boolean[] hits;
    boolean active;

    PositionVisual(long startTime, boolean isLong, double entry, double stop, double risk, int targetCount)
    {
      this.startTime = startTime;
      this.endTime = startTime;
      this.isLong = isLong;
      this.entry = entry;
      this.stop = stop;
      this.risk = risk;
      this.hits = new boolean[Math.max(2, Math.min(4, targetCount))];
      this.active = true;
    }

    void markHits(double high, double low)
    {
      int dir = isLong ? 1 : -1;
      for (int i = 0; i < hits.length; i++) {
        double tp = entry + dir * risk * (i + 1);
        if (isLong ? high >= tp : low <= tp) hits[i] = true;
      }
    }

    void closeAt(long t)
    {
      endTime = t;
      active = false;
    }
  }

  private final List<TradeMark> tradeMarks = new ArrayList<>();
  private final List<TradeMark> simMarks = new ArrayList<>();
  private final List<ClosedTrade> closedTrades = new ArrayList<>();
  private final List<ClosedTrade> simTrades = new ArrayList<>();
  private final List<PositionVisual> positionVisuals = new ArrayList<>();
  private final List<HudRow> hudRows = new ArrayList<>();

  private volatile boolean strategyArmed;
  private volatile double entryPrice;
  private volatile double stopPrice;
  private volatile double riskDist;
  private volatile int positionDir; // +1 long, -1 short, 0 flat
  private volatile int entryIndex = -1;
  private volatile int liveTrend;
  private volatile int maxTargetHit;
  private volatile String exitReason = "";
  private volatile String simVerdict = "";
  private volatile String hudTitle = "Trend Target Ribbon";
  private volatile Color hudBadgeColor = C_HUD;
  private volatile String hudHint = "";

  @Override
  public void initialize(Defaults defaults)
  {
    var sd = createSD();
    var tab = sd.addTab("Trend");
    var grp = tab.addGroup("ALMA / Confirmation");
    grp.addRow(new IntegerDescriptor(ALMA_LEN, "ALMA Length", 34, 5, 200, 1));
    grp.addRow(new DoubleDescriptor(ALMA_OFFSET, "ALMA Offset", 0.85, 0.0, 1.0, 0.05));
    grp.addRow(new DoubleDescriptor(ALMA_SIGMA, "ALMA Sigma", 6.0, 1.0, 15.0, 0.5));
    grp.addRow(new IntegerDescriptor(DEV_LEN, "Deviation Length", 34, 5, 200, 1));
    grp.addRow(new DoubleDescriptor(DEV_MULT, "Trend Confirmation", 0.65, 0.1, 3.0, 0.05));
    grp.addRow(new IntegerDescriptor(SLOPE_LEN, "Slope Length", 3, 1, 20, 1));
    grp.addRow(new DoubleDescriptor(SLOPE_MIN, "Minimum Slope", 0.08, 0.0, 1.0, 0.01));
    grp.addRow(new IntegerDescriptor(ATR_LEN, "ATR Length", 14, 5, 50, 1));

    tab = sd.addTab("Position");
    grp = tab.addGroup("Risk / Targets");
    grp.addRow(new IntegerDescriptor(STOP_LOOKBACK, "Stop Structure Lookback", 12, 3, 50, 1));
    grp.addRow(new DoubleDescriptor(MIN_STOP_ATR, "Minimum Stop ATR", 0.75, 0.25, 3.0, 0.05));
    grp.addRow(new DoubleDescriptor(MAX_STOP_ATR, "Maximum Stop ATR", 3.0, 1.0, 8.0, 0.25));
    grp.addRow(new IntegerDescriptor(TARGET_COUNT, "Profit Targets", 4, 2, 4, 1));
    grp.addRow(new DoubleDescriptor(ZONE_PCT, "Target Zone Size", 0.06, 0.01, 0.20, 0.01));
    grp.addRow(new IntegerDescriptor(EXTEND_BARS, "Projection Length (bars)", 30, 10, 100, 1));
    grp.addRow(new IntegerDescriptor(KEEP_POSITIONS, "Positions On Chart", 4, 1, 5, 1));
    grp.addRow(new IntegerDescriptor(CONTRACTS, "Contracts / Shares", 1, 1, 500, 1));
    grp.addRow(new BooleanDescriptor(AUTO_BACKTEST, "Auto backtest on chart history", true));

    tab = sd.addTab("Display");
    grp = tab.addGroup("Overlay");
    grp.addRow(new BooleanDescriptor(SHOW_HUD, "Show HUD + stats", true));
    grp.addRow(new BooleanDescriptor(SHOW_MARKERS, "Show flip / entry markers", true));
    grp.addRow(new BooleanDescriptor(SHOW_LEVELS, "Show entry / SL / targets / risk box", true));
    grp.addRow(new BooleanDescriptor(SHOW_RIBBON, "Show trend ribbon", true));
    grp.addRow(new BooleanDescriptor(PAINT_BARS, "Color candles by conviction", true));
    grp.addRow(new PathDescriptor(ALMA_PATH, "ALMA", C_ALMA, 1.5f, null, true, false, true));
    grp.addRow(new PathDescriptor(UPPER_PATH, "Upper confirm", new Color(0, 255, 0, 90), 1.0f, new float[] { 4f, 3f }, true, false, true));
    grp.addRow(new PathDescriptor(LOWER_PATH, "Lower confirm", new Color(255, 0, 102, 90), 1.0f, new float[] { 4f, 3f }, true, false, true));

    var rd = createRD();
    rd.declarePath(Values.ALMA, ALMA_PATH);
    rd.declarePath(Values.UPPER, UPPER_PATH);
    rd.declarePath(Values.LOWER, LOWER_PATH);
    rd.setLabelPrefix("TTR");
  }

  @Override
  public void onLoad(Defaults defaults)
  {
    strategyArmed = false;
    resetTradeState();
    tradeMarks.clear();
    closedTrades.clear();
    simMarks.clear();
    simTrades.clear();
    synchronized (positionVisuals) { positionVisuals.clear(); }
    simVerdict = "";
    hudBadgeColor = C_HUD;
    hudTitle = "Trend Target Ribbon  [" + BUILD + "]";
    hudHint = "ALMA flip — retire/re-ajoute l'etude apres reload";
    synchronized (hudRows) {
      hudRows.clear();
      hudRows.add(HudRow.section("STATUS"));
      hudRows.add(HudRow.kv("Mode", "Chargé — en attente de calcul"));
    }
  }

  @Override
  protected void calculate(int index, DataContext ctx)
  {
    DataSeries series = ctx.getDataSeries();
    if (series == null || index < 2) return;

    computeIndicators(index, series);
    series.setComplete(index);

    if (index >= series.size() - 1) {
      if (getSettings().getBoolean(AUTO_BACKTEST, true)) {
        runHistoricalBacktest(series);
      }
      refreshHud(series, index, 0);
      drawOverlay(ctx);
    }
  }

  @Override
  public void onBarUpdate(DataContext ctx)
  {
    DataSeries series = ctx.getDataSeries();
    if (series == null || series.size() < 10) return;
    int index = series.size() - 1;
    if (getSettings().getBoolean(AUTO_BACKTEST, true)) {
      runHistoricalBacktest(series);
    }
    refreshHud(series, index, 0);
    drawOverlay(ctx);
  }

  @Override
  public void onActivate(OrderContext ctx)
  {
    strategyArmed = true;
    DataContext dc = ctx.getDataContext();
    if (dc != null && dc.getDataSeries() != null) {
      DataSeries s = dc.getDataSeries();
      refreshHud(s, Math.max(0, s.size() - 1), ctx.getPosition());
      drawOverlay(dc);
    }
    else {
      hudBadgeColor = C_BULL;
      hudTitle = "ARMED  [" + BUILD + "]";
      hudHint = "Waiting for ALMA flip";
      notifyRedraw();
    }
  }

  @Override
  public void onDeactivate(OrderContext ctx)
  {
    strategyArmed = false;
    hudBadgeColor = C_HUD;
    hudTitle = "OFF  [" + BUILD + "]";
    hudHint = "Strategy deactivated";
    DataContext dc = ctx.getDataContext();
    if (dc != null) drawOverlay(dc);
    else notifyRedraw();
  }

  @Override
  public void onBarClose(OrderContext ctx)
  {
    if (!strategyArmed) return;
    DataSeries series = ctx.getDataContext().getDataSeries();
    if (series == null || series.size() < 5) return;
    int index = series.size() - 1;
    if (!series.isBarComplete(index)) index--;
    if (index < 2) return;

    computeIndicators(index, series);
    Integer flip = series.getInt(index, Values.FLIP);
    int pos = ctx.getPosition();

    // Manage open position first (SL on this bar)
    if (pos != 0 && positionDir != 0) {
      double low = series.getLow(index);
      double high = series.getHigh(index);
      long t = series.getStartTime(index);
      updateMaxTargetHit(high, low);
      boolean longPos = positionDir > 0;
      if (longPos && low <= stopPrice) {
        flattenLive(ctx, "SL", t, Math.min(series.getClose(index), stopPrice));
        pos = 0;
      }
      else if (!longPos && high >= stopPrice) {
        flattenLive(ctx, "SL", t, Math.max(series.getClose(index), stopPrice));
        pos = 0;
      }
    }

    // Flip ends prior position then opens new (Pine order)
    if (flip != null && flip != FLIP_NONE) {
      long t = series.getStartTime(index);
      double close = series.getClose(index);
      if (pos != 0 && positionDir != 0) {
        flattenLive(ctx, "FLIP", t, close);
      }
      openLive(ctx, series, index, flip > 0);
    }

    refreshHud(series, index, ctx.getPosition());
    drawOverlay(ctx.getDataContext());
  }

  // ---------- Indicators (exact Pine math) ----------

  private void computeIndicators(int index, DataSeries series)
  {
    int almaLen = Math.max(5, getSettings().getInteger(ALMA_LEN, 34));
    double offset = getSettings().getDouble(ALMA_OFFSET, 0.85);
    double sigma = Math.max(1.0, getSettings().getDouble(ALMA_SIGMA, 6.0));
    int devLen = Math.max(5, getSettings().getInteger(DEV_LEN, 34));
    double devMult = getSettings().getDouble(DEV_MULT, 0.65);
    int slopeLen = Math.max(1, getSettings().getInteger(SLOPE_LEN, 3));
    double slopeMin = getSettings().getDouble(SLOPE_MIN, 0.08);
    int atrLen = Math.max(5, getSettings().getInteger(ATR_LEN, 14));

    Double alma = computeAlma(series, index, almaLen, offset, sigma);
    if (alma == null) return;
    series.setDouble(index, Values.ALMA, alma);

    Double dev = series.std(index, devLen, Enums.BarInput.CLOSE);
    if (dev == null) return;
    series.setDouble(index, Values.DEV, dev);

    Double atr = series.atr(index, atrLen);
    if (atr == null || atr <= 0) return;
    series.setDouble(index, Values.ATR, atr);

    series.setDouble(index, Values.UPPER, alma + dev * devMult);
    series.setDouble(index, Values.LOWER, alma - dev * devMult);

    double slopeScore = 0;
    if (index >= slopeLen) {
      Double prevAlma = series.getDouble(index - slopeLen, Values.ALMA);
      if (prevAlma != null) slopeScore = (alma - prevAlma) / atr;
    }
    series.setDouble(index, Values.SLOPE, slopeScore);

    double close = series.getClose(index);
    Double upper = series.getDouble(index, Values.UPPER);
    Double lower = series.getDouble(index, Values.LOWER);
    boolean bullSetup = slopeScore > slopeMin && upper != null && close > upper;
    boolean bearSetup = slopeScore < -slopeMin && lower != null && close < lower;

    int prevTrend = 0;
    if (index > 0) {
      Integer pt = series.getInt(index - 1, Values.TREND);
      if (pt != null) prevTrend = pt;
    }

    int trend = prevTrend;
    int flip = FLIP_NONE;
    if (trend != 1 && bullSetup) {
      trend = 1;
      flip = FLIP_BULL;
    }
    else if (trend != -1 && bearSetup) {
      trend = -1;
      flip = FLIP_BEAR;
    }
    series.setInt(index, Values.TREND, trend);
    series.setInt(index, Values.FLIP, flip);
    liveTrend = trend;

    // Ribbon edge + conviction (Pine visual)
    double edge = alma;
    if (trend == 1) edge = alma - dev * 0.55;
    else if (trend == -1) edge = alma + dev * 0.55;
    series.setDouble(index, Values.EDGE, edge);

    double distanceScore = Math.abs(close - alma) / atr;
    double conviction = Math.max(0, Math.min(1.0, Math.abs(slopeScore) * 2.0 + distanceScore * 0.35));
    series.setDouble(index, Values.CONVICTION, conviction);

    if (getSettings().getBoolean(PAINT_BARS, true) && trend != 0) {
      Color base = trend > 0 ? C_BULL : C_BEAR;
      int alpha = (int) Math.round(70 + conviction * 160);
      alpha = Math.max(70, Math.min(255, alpha));
      series.setPriceBarColor(index, new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
    }
  }

  /** TradingView ta.alma(series, length, offset, sigma). */
  private static Double computeAlma(DataSeries series, int index, int length, double offset, double sigma)
  {
    if (index < length - 1) return null;
    double m = offset * (length - 1);
    double s = length / sigma;
    double norm = 0;
    double sum = 0;
    for (int i = 0; i < length; i++) {
      double w = Math.exp(-((i - m) * (i - m)) / (2.0 * s * s));
      norm += w;
      sum += series.getClose(index - (length - 1 - i)) * w;
    }
    return norm > 0 ? sum / norm : null;
  }

  // ---------- Live entries ----------

  private void openLive(OrderContext ctx, DataSeries series, int index, boolean isLong)
  {
    Double atr = series.getDouble(index, Values.ATR);
    if (atr == null || atr <= 0) return;

    double entry = series.getClose(index);
    double risk = computeRiskDist(series, index, isLong, entry, atr);
    if (risk <= 0) return;

    int qty = Math.max(1, getSettings().getInteger(CONTRACTS, 1));
    try {
      if (isLong) ctx.buy(qty);
      else ctx.sell(qty);
      positionDir = isLong ? 1 : -1;
      entryPrice = entry;
      riskDist = risk;
      stopPrice = entry - positionDir * risk;
      entryIndex = index;
      maxTargetHit = 0;
      exitReason = "";
      addTradeMark(series.getStartTime(index), entry, isLong, true, isLong ? "LONG" : "SHORT");
      debug("TTR " + (isLong ? "LONG" : "SHORT") + " @ " + entry + " SL " + stopPrice + " R=" + risk);
    }
    catch (Exception ex) {
      hudHint = safe(ex.getMessage());
    }
  }

  private void flattenLive(OrderContext ctx, String reason, long barTime, double price)
  {
    try {
      boolean wasLong = positionDir > 0;
      ctx.closeAtMarket();
      exitReason = reason;
      if (entryPrice > 0 && riskDist > 0) {
        ClosedTrade t = new ClosedTrade(wasLong, entryPrice, price, riskDist, reason, maxTargetHit);
        synchronized (closedTrades) { closedTrades.add(t); }
      }
      addTradeMark(barTime, price, wasLong, false, "OUT " + reason);
      resetTradeState();
    }
    catch (Exception ex) {
      hudHint = safe(ex.getMessage());
    }
  }

  private void resetTradeState()
  {
    entryPrice = 0;
    stopPrice = Double.NaN;
    riskDist = 0;
    positionDir = 0;
    entryIndex = -1;
    maxTargetHit = 0;
  }

  /**
   * Pine: structureStop = lowest(low, lookback)[1] / highest(high, lookback)[1]
   * riskDist = clamp(rawRisk, atr*min, atr*max)
   */
  private double computeRiskDist(DataSeries series, int index, boolean isLong, double entry, double atr)
  {
    int lookback = Math.max(3, getSettings().getInteger(STOP_LOOKBACK, 12));
    double minAtr = getSettings().getDouble(MIN_STOP_ATR, 0.75);
    double maxAtr = getSettings().getDouble(MAX_STOP_ATR, 3.0);
    if (maxAtr < minAtr) maxAtr = minAtr;

    if (index < 1) return atr * minAtr;
    int end = index - 1; // [1] shift
    int from = Math.max(0, end - lookback + 1);
    double structure;
    if (isLong) {
      structure = series.getLow(from);
      for (int i = from + 1; i <= end; i++) structure = Math.min(structure, series.getLow(i));
    }
    else {
      structure = series.getHigh(from);
      for (int i = from + 1; i <= end; i++) structure = Math.max(structure, series.getHigh(i));
    }

    double raw = isLong ? (entry - structure) : (structure - entry);
    if (Double.isNaN(raw) || raw <= 0) raw = atr * minAtr;
    double minRisk = atr * minAtr;
    double maxRisk = atr * maxAtr;
    return Math.min(Math.max(raw, minRisk), maxRisk);
  }

  private void updateMaxTargetHit(double high, double low)
  {
    if (riskDist <= 0 || positionDir == 0) return;
    int n = Math.max(2, Math.min(4, getSettings().getInteger(TARGET_COUNT, 4)));
    for (int i = 0; i < n; i++) {
      double tp = entryPrice + positionDir * riskDist * (i + 1);
      boolean hit = positionDir > 0 ? high >= tp : low <= tp;
      if (hit) maxTargetHit = Math.max(maxTargetHit, i + 1);
    }
  }

  // ---------- Backtest (same rules as Pine) ----------

  private void runHistoricalBacktest(DataSeries series)
  {
    if (series == null || series.size() < 40) {
      simVerdict = "Historique trop court";
      return;
    }
    int size = series.size();
    int end = series.isBarComplete(size - 1) ? size - 1 : size - 2;
    if (end < 40) {
      simVerdict = "Pas assez de barres completes";
      return;
    }

    int almaLen = Math.max(5, getSettings().getInteger(ALMA_LEN, 34));
    int atrLen = Math.max(5, getSettings().getInteger(ATR_LEN, 14));
    int start = Math.max(almaLen, atrLen) + Math.max(1, getSettings().getInteger(SLOPE_LEN, 3));

    for (int i = 0; i <= end; i++) {
      computeIndicators(i, series);
    }

    synchronized (simTrades) { simTrades.clear(); }
    synchronized (simMarks) { simMarks.clear(); }
    synchronized (positionVisuals) { positionVisuals.clear(); }

    boolean inPos = false;
    boolean longPos = false;
    double entry = 0;
    double stop = Double.NaN;
    double risk = 0;
    int maxHit = 0;
    int flips = 0;
    PositionVisual curVis = null;
    int keep = Math.max(1, Math.min(5, getSettings().getInteger(KEEP_POSITIONS, 4)));
    int targets = Math.max(2, Math.min(4, getSettings().getInteger(TARGET_COUNT, 4)));

    for (int i = start; i <= end; i++) {
      Integer flipObj = series.getInt(i, Values.FLIP);
      int flip = flipObj == null ? FLIP_NONE : flipObj;
      double close = series.getClose(i);
      double low = series.getLow(i);
      double high = series.getHigh(i);
      long t = series.getStartTime(i);
      Double atr = series.getDouble(i, Values.ATR);

      if (inPos) {
        if (risk > 0) {
          for (int k = 0; k < targets; k++) {
            double tp = entry + (longPos ? 1 : -1) * risk * (k + 1);
            if (longPos ? high >= tp : low <= tp) maxHit = Math.max(maxHit, k + 1);
          }
        }
        if (curVis != null) {
          curVis.markHits(high, low);
          curVis.endTime = t;
        }

        String reason = null;
        double exitPx = close;
        if (longPos && !Double.isNaN(stop) && low <= stop) {
          reason = "SL";
          exitPx = Math.min(close, stop);
        }
        else if (!longPos && !Double.isNaN(stop) && high >= stop) {
          reason = "SL";
          exitPx = Math.max(close, stop);
        }
        else if (flip != FLIP_NONE) {
          reason = "FLIP";
          exitPx = close;
        }

        if (reason != null) {
          ClosedTrade ct = new ClosedTrade(longPos, entry, exitPx, risk, reason, maxHit);
          synchronized (simTrades) { simTrades.add(ct); }
          addSimMark(t, exitPx, longPos, false, "OUT " + reason);
          if (curVis != null) {
            curVis.closeAt(t);
            pushVisual(curVis, keep);
            curVis = null;
          }
          inPos = false;
          entry = 0;
          stop = Double.NaN;
          risk = 0;
          maxHit = 0;
        }
      }

      if (!inPos && flip != FLIP_NONE && atr != null && atr > 0) {
        boolean goLong = flip == FLIP_BULL;
        flips++;
        entry = close;
        longPos = goLong;
        risk = computeRiskDist(series, i, goLong, entry, atr);
        stop = entry - (goLong ? 1 : -1) * risk;
        maxHit = 0;
        inPos = true;
        curVis = new PositionVisual(t, goLong, entry, stop, risk, targets);
        addSimMark(t, entry, goLong, true, goLong ? "LONG" : "SHORT");
      }
    }

    if (inPos && entry > 0) {
      long tEnd = series.getStartTime(end);
      double exitPx = series.getClose(end);
      ClosedTrade ct = new ClosedTrade(longPos, entry, exitPx, risk, "EOD", maxHit);
      synchronized (simTrades) { simTrades.add(ct); }
      addSimMark(tEnd, exitPx, longPos, false, "OUT EOD");
      if (curVis != null) {
        curVis.active = true;
        curVis.endTime = tEnd;
        pushVisual(curVis, keep);
      }
    }

    Stats sn = computeStats(simTrades);
    if (sn.trades == 0) {
      simVerdict = flips == 0
          ? "0 trade — aucun flip ALMA (assouplir confirmation / TF)"
          : "0 trade ferme — flips=" + flips;
    }
    else if (sn.netR > 0 && sn.profitFactor >= 1.0) {
      simVerdict = String.format("RENTABLE  net %+.1fR  PF %.2f  (%d trades)",
          sn.netR, sn.profitFactor, sn.trades);
    }
    else if (sn.netR > 0) {
      simVerdict = String.format("MARGINAL  net %+.1fR  PF %.2f  (%d trades)",
          sn.netR, sn.profitFactor, sn.trades);
    }
    else {
      simVerdict = String.format("PAS RENTABLE  net %+.1fR  PF %.2f  (%d trades)",
          sn.netR, sn.profitFactor, sn.trades);
    }
  }

  private void addTradeMark(long time, double price, boolean isLong, boolean isEntry, String label)
  {
    synchronized (tradeMarks) {
      tradeMarks.add(new TradeMark(time, price, isLong, isEntry, label));
      while (tradeMarks.size() > 80) tradeMarks.remove(0);
    }
  }

  private void addSimMark(long time, double price, boolean isLong, boolean isEntry, String label)
  {
    synchronized (simMarks) {
      simMarks.add(new TradeMark(time, price, isLong, isEntry, label));
    }
  }

  // ---------- HUD ----------

  private void refreshHud(DataSeries series, int index, int position)
  {
    List<HudRow> rows = new ArrayList<>();
    Stats sn = computeStats(simTrades);
    double close = series != null && index >= 0 ? series.getClose(index) : 0;

    if (sn.trades > 0) {
      boolean good = sn.netR > 0 && sn.profitFactor >= 1.0;
      hudBadgeColor = good ? C_PROFIT : (sn.netR > 0 ? new Color(120, 100, 30) : C_LOSS);
      hudTitle = (good ? "RENTABLE" : (sn.netR > 0 ? "MARGINAL" : "PAS RENTABLE"))
          + "  [" + BUILD + "]";

      rows.add(HudRow.section("PERFORMANCE"));
      rows.add(HudRow.kv("Net R", String.format("%+.1fR", sn.netR),
          sn.netR >= 0 ? C_PROFIT : C_LOSS));
      rows.add(HudRow.kv("Profit Factor", String.format("%.2f", sn.profitFactor),
          sn.profitFactor >= 1 ? C_PROFIT : C_LOSS));
      rows.add(HudRow.kv("Expectancy", String.format("%+.2fR / trade", sn.expectancy)));
      rows.add(HudRow.kv("Trades", Integer.toString(sn.trades)));
      rows.add(HudRow.kv("Win rate", String.format("%.0f%%", sn.winRate)));
      rows.add(HudRow.kv("Avg win / loss", String.format("%+.1fR  /  %+.1fR", sn.avgWin, sn.avgLoss)));
      rows.add(HudRow.kv("Payoff", String.format("%.2f", sn.payoff)));

      rows.add(HudRow.section("SIDES"));
      rows.add(HudRow.kv("Long", String.format("%d · %.0f%% win", sn.longs, sn.longWinRate)));
      rows.add(HudRow.kv("Short", String.format("%d · %.0f%% win", sn.shorts, sn.shortWinRate)));

      rows.add(HudRow.section("EXITS"));
      rows.add(HudRow.kv("SL", String.format("%d  (%.0f%%)", sn.nSl, sn.pct(sn.nSl))));
      rows.add(HudRow.kv("FLIP", String.format("%d  (%.0f%%)", sn.nFlip, sn.pct(sn.nFlip))));
      rows.add(HudRow.kv("EOD", Integer.toString(sn.nEod)));
      rows.add(HudRow.kv("Hit ≥1R / ≥2R", sn.hit1 + " / " + sn.hit2));
      rows.add(HudRow.kv("Hit ≥3R / ≥4R", sn.hit3 + " / " + sn.hit4));

      rows.add(HudRow.section("SETUP"));
      rows.add(HudRow.kv("Engine", "ALMA flip + structure SL"));
      rows.add(HudRow.kv("ALMA", getSettings().getInteger(ALMA_LEN, 34)
          + " / " + getSettings().getDouble(ALMA_OFFSET, 0.85)
          + " / " + getSettings().getDouble(ALMA_SIGMA, 6.0)));
      rows.add(HudRow.kv("Confirm", "±" + getSettings().getDouble(DEV_MULT, 0.65) + "σ · slope≥"
          + getSettings().getDouble(SLOPE_MIN, 0.08)));
      rows.add(HudRow.kv("Stop", "swing " + getSettings().getInteger(STOP_LOOKBACK, 12)
          + " · [" + getSettings().getDouble(MIN_STOP_ATR, 0.75)
          + "–" + getSettings().getDouble(MAX_STOP_ATR, 3.0) + "] ATR"));
      rows.add(HudRow.kv("Targets", "1R…" + getSettings().getInteger(TARGET_COUNT, 4) + "R (exit=SL|FLIP)"));

      if (position != 0 && riskDist > 0) {
        double uR = position > 0
            ? (close - entryPrice) / riskDist
            : (entryPrice - close) / riskDist;
        hudHint = String.format("LIVE %s  %+.1fR  SL %s",
            position > 0 ? "LONG" : "SHORT", uR, fmt(stopPrice));
      }
      else {
        hudHint = sn.netR > 0
            ? "HINT: edge OK — valide out-of-sample"
            : "HINT: flip exits = runners coupes — normal vs trail";
      }
    }
    else {
      hudBadgeColor = strategyArmed ? C_BULL : C_HUD;
      hudTitle = (strategyArmed ? "ARMED" : "BACKTEST") + "  [" + BUILD + "]";
      rows.add(HudRow.section("STATUS"));
      rows.add(HudRow.kv("Trades", "0 sur cet historique"));
      rows.add(HudRow.kv("Diagnostic", Util.isEmpty(simVerdict) ? "En cours" : simVerdict));
      rows.add(HudRow.section("SETUP"));
      rows.add(HudRow.kv("Engine", "ALMA flip + structure SL"));
      rows.add(HudRow.kv("Exit", "SL ou flip oppose (logique Pine)"));
      hudHint = "Charge plus d'historique ou assouplir Trend Confirmation";
    }

    Integer trend = series != null ? series.getInt(index, Values.TREND) : null;
    if (trend != null && trend != 0) {
      rows.add(HudRow.kv("Trend now", trend > 0 ? "BULL" : "BEAR",
          trend > 0 ? C_BULL : C_BEAR));
    }

    synchronized (hudRows) {
      hudRows.clear();
      hudRows.addAll(rows);
    }
  }

  private static class Stats
  {
    int trades, longs, shorts, wins, longWins, shortWins;
    int nSl, nFlip, nEod;
    int hit1, hit2, hit3, hit4;
    double netR, avgWin, avgLoss, winRate, longWinRate, shortWinRate, profitFactor;
    double expectancy, payoff;

    double pct(int n) { return trades > 0 ? (100.0 * n / trades) : 0; }
  }

  private Stats computeStats(List<ClosedTrade> source)
  {
    Stats s = new Stats();
    List<ClosedTrade> copy;
    synchronized (source) { copy = new ArrayList<>(source); }
    if (copy.isEmpty()) return s;

    double sumWin = 0, sumLoss = 0;
    int nWin = 0, nLoss = 0;
    double grossWin = 0, grossLoss = 0;

    for (ClosedTrade t : copy) {
      s.trades++;
      s.netR += t.pnlR;
      if (t.isLong) {
        s.longs++;
        if (t.pnlR > 0) { s.longWins++; s.wins++; sumWin += t.pnlR; nWin++; grossWin += t.pnlR; }
        else if (t.pnlR < 0) { sumLoss += t.pnlR; nLoss++; grossLoss += -t.pnlR; }
      }
      else {
        s.shorts++;
        if (t.pnlR > 0) { s.shortWins++; s.wins++; sumWin += t.pnlR; nWin++; grossWin += t.pnlR; }
        else if (t.pnlR < 0) { sumLoss += t.pnlR; nLoss++; grossLoss += -t.pnlR; }
      }
      if ("SL".equals(t.reason)) s.nSl++;
      else if ("FLIP".equals(t.reason)) s.nFlip++;
      else if ("EOD".equals(t.reason)) s.nEod++;
      if (t.maxTargetHit >= 1) s.hit1++;
      if (t.maxTargetHit >= 2) s.hit2++;
      if (t.maxTargetHit >= 3) s.hit3++;
      if (t.maxTargetHit >= 4) s.hit4++;
    }

    s.winRate = s.trades > 0 ? (100.0 * s.wins / s.trades) : 0;
    s.longWinRate = s.longs > 0 ? (100.0 * s.longWins / s.longs) : 0;
    s.shortWinRate = s.shorts > 0 ? (100.0 * s.shortWins / s.shorts) : 0;
    s.avgWin = nWin > 0 ? sumWin / nWin : 0;
    s.avgLoss = nLoss > 0 ? sumLoss / nLoss : 0;
    s.profitFactor = grossLoss > 0 ? grossWin / grossLoss : (grossWin > 0 ? 99.0 : 0);
    s.expectancy = s.trades > 0 ? s.netR / s.trades : 0;
    s.payoff = (s.avgLoss < 0) ? (s.avgWin / Math.abs(s.avgLoss)) : (s.avgWin > 0 ? 99.0 : 0);
    return s;
  }

  // ---------- Drawing ----------

  private void pushVisual(PositionVisual vis, int keep)
  {
    synchronized (positionVisuals) {
      positionVisuals.add(vis);
      while (positionVisuals.size() > keep) positionVisuals.remove(0);
    }
  }

  private void drawOverlay(DataContext ctx)
  {
    beginFigureUpdate();
    clearFigures();
    DataSeries series = ctx.getDataSeries();

    if (series != null && getSettings().getBoolean(SHOW_RIBBON, true)) {
      RibbonFigure ribbon = buildRibbon(series);
      if (ribbon != null) {
        ribbon.setUnderlay(true);
        addFigure(ribbon);
      }
    }

    if (getSettings().getBoolean(SHOW_LEVELS, true)) {
      double zonePct = getSettings().getDouble(ZONE_PCT, 0.06);
      long extendMs = estimateBarMs(series) * Math.max(10, getSettings().getInteger(EXTEND_BARS, 30));
      long lastT = series != null && series.size() > 0
          ? series.getStartTime(series.size() - 1) : System.currentTimeMillis();

      List<PositionVisual> plans;
      synchronized (positionVisuals) { plans = new ArrayList<>(positionVisuals); }
      for (PositionVisual p : plans) {
        long x2 = p.active ? (lastT + extendMs) : p.endTime;
        addFigure(new PositionPlanFigure(p, x2, zonePct));
      }

      // Live armed position if not already in visuals
      if (positionDir != 0 && entryPrice > 0 && riskDist > 0) {
        boolean covered = false;
        for (PositionVisual p : plans) {
          if (p.active && Math.abs(p.entry - entryPrice) < 1e-9) { covered = true; break; }
        }
        if (!covered) {
          int n = Math.max(2, Math.min(4, getSettings().getInteger(TARGET_COUNT, 4)));
          PositionVisual live = new PositionVisual(
              series != null && entryIndex >= 0 ? series.getStartTime(Math.min(entryIndex, series.size() - 1)) : lastT,
              positionDir > 0, entryPrice, stopPrice, riskDist, n);
          live.active = true;
          live.endTime = lastT;
          addFigure(new PositionPlanFigure(live, lastT + extendMs, zonePct));
        }
      }
    }

    if (getSettings().getBoolean(SHOW_MARKERS, true)) {
      List<TradeMark> marks;
      synchronized (simMarks) { marks = new ArrayList<>(simMarks); }
      if (marks.isEmpty()) {
        synchronized (tradeMarks) { marks = new ArrayList<>(tradeMarks); }
      }
      for (TradeMark m : marks) {
        if (!m.isEntry) continue; // Pine only plots flip diamonds on entry
        Color fill = m.isLong ? C_BULL : C_BEAR;
        Enums.Position pos = m.isLong ? Enums.Position.BOTTOM : Enums.Position.TOP;
        Marker mk = new Marker(new Coordinate(m.time, m.price), Enums.MarkerType.DIAMOND,
            Enums.Size.SMALL, pos, fill, Color.WHITE);
        mk.setTextValue(m.isLong ? "▲" : "▼");
        mk.setTextPosition(pos);
        addFigure(mk);
      }
    }

    if (getSettings().getBoolean(SHOW_HUD, true)) {
      List<HudRow> rows;
      synchronized (hudRows) { rows = new ArrayList<>(hudRows); }
      addFigure(new StatsBoard(hudTitle, hudBadgeColor, rows, hudHint));
    }

    endFigureUpdate();
    notifyRedraw();
  }

  private static long estimateBarMs(DataSeries series)
  {
    if (series == null || series.size() < 2) return 60_000L;
    int n = series.size();
    long a = series.getStartTime(n - 1);
    long b = series.getStartTime(n - 2);
    long d = Math.abs(a - b);
    return d > 0 ? d : 60_000L;
  }

  private RibbonFigure buildRibbon(DataSeries series)
  {
    int size = series.size();
    if (size < 5) return null;
    int end = series.isBarComplete(size - 1) ? size - 1 : Math.max(0, size - 2);
    int start = Math.max(0, end - 800); // cap for perf
    List<Long> times = new ArrayList<>();
    List<Double> alma = new ArrayList<>();
    List<Double> edge = new ArrayList<>();
    List<Integer> trend = new ArrayList<>();
    for (int i = start; i <= end; i++) {
      Double a = series.getDouble(i, Values.ALMA);
      Double e = series.getDouble(i, Values.EDGE);
      Integer tr = series.getInt(i, Values.TREND);
      Integer flip = series.getInt(i, Values.FLIP);
      if (a == null || e == null || tr == null || tr == 0) continue;
      if (flip != null && flip != FLIP_NONE) continue; // Pine breaks ribbon on flip bar
      times.add(series.getStartTime(i));
      alma.add(a);
      edge.add(e);
      trend.add(tr);
    }
    if (times.size() < 2) return null;
    return new RibbonFigure(times, alma, edge, trend);
  }

  /** Filled ribbon between ALMA and edge, colored by trend (Pine-like). */
  private static class RibbonFigure extends Figure
  {
    private final List<Long> times;
    private final List<Double> alma;
    private final List<Double> edge;
    private final List<Integer> trend;

    RibbonFigure(List<Long> times, List<Double> alma, List<Double> edge, List<Integer> trend)
    {
      this.times = times;
      this.alma = alma;
      this.edge = edge;
      this.trend = trend;
    }

    @Override public boolean isVisible(DrawContext ctx) { return true; }

    @Override
    public void layout(DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      setBounds(new Rectangle2D.Double(gb.getX(), gb.getY(), gb.getWidth(), gb.getHeight()));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      int i = 0;
      while (i < times.size()) {
        int tr = trend.get(i);
        int j = i + 1;
        while (j < times.size() && trend.get(j) == tr) j++;
        Path2D.Double fill = new Path2D.Double();
        Path2D.Double edgePath = new Path2D.Double();
        Path2D.Double almaPath = new Path2D.Double();
        boolean first = true;
        for (int k = i; k < j; k++) {
          double x = ctx.translateTimeD(times.get(k));
          double ye = ctx.translateValueD(edge.get(k));
          double ya = ctx.translateValueD(alma.get(k));
          if (first) {
            fill.moveTo(x, ye);
            edgePath.moveTo(x, ye);
            almaPath.moveTo(x, ya);
            first = false;
          }
          else {
            fill.lineTo(x, ye);
            edgePath.lineTo(x, ye);
            almaPath.lineTo(x, ya);
          }
        }
        for (int k = j - 1; k >= i; k--) {
          double x = ctx.translateTimeD(times.get(k));
          double ya = ctx.translateValueD(alma.get(k));
          fill.lineTo(x, ya);
        }
        fill.closePath();
        Color col = tr > 0 ? C_BULL : C_BEAR;
        gc.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 28));
        gc.fill(fill);
        gc.setStroke(new BasicStroke(2.5f));
        gc.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 110));
        gc.draw(edgePath);
        gc.setStroke(new BasicStroke(1.2f));
        gc.setColor(new Color(255, 255, 255, 180));
        gc.draw(almaPath);
        i = j;
      }
    }
  }

  /** Entry / SL / TP / risk box for one position (Pine plan look). */
  private static class PositionPlanFigure extends Figure
  {
    private final PositionVisual p;
    private final long endTime;
    private final double zonePct;

    PositionPlanFigure(PositionVisual p, long endTime, double zonePct)
    {
      this.p = p;
      this.endTime = Math.max(endTime, p.startTime + 1);
      this.zonePct = zonePct;
    }

    @Override public boolean isVisible(DrawContext ctx) { return true; }

    @Override
    public void layout(DrawContext ctx)
    {
      double x1 = ctx.translateTimeD(p.startTime);
      double x2 = ctx.translateTimeD(endTime);
      double yTop = ctx.translateValueD(Math.max(p.entry, p.stop + p.risk * p.hits.length));
      double yBot = ctx.translateValueD(Math.min(p.entry, p.stop - p.risk * p.hits.length));
      double top = Math.min(yTop, yBot);
      double bot = Math.max(yTop, yBot);
      setBounds(new Rectangle2D.Double(Math.min(x1, x2), top - 4, Math.abs(x2 - x1) + 80, bot - top + 20));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      double x1 = ctx.translateTimeD(p.startTime);
      double x2 = ctx.translateTimeD(endTime);
      if (x2 < x1) { double tmp = x1; x1 = x2; x2 = tmp; }
      Color side = p.isLong ? C_BULL : C_BEAR;

      // Risk box
      double yEntry = ctx.translateValueD(p.entry);
      double yStop = ctx.translateValueD(p.stop);
      double top = Math.min(yEntry, yStop);
      double h = Math.abs(yEntry - yStop);
      gc.setColor(new Color(C_SL.getRed(), C_SL.getGreen(), C_SL.getBlue(), p.active ? 35 : 18));
      gc.fill(new Rectangle2D.Double(x1, top, x2 - x1, Math.max(1, h)));

      // Entry glow + core
      gc.setStroke(new BasicStroke(8f));
      gc.setColor(new Color(side.getRed(), side.getGreen(), side.getBlue(), p.active ? 55 : 25));
      gc.draw(new Line2D.Double(x1, yEntry, x2, yEntry));
      gc.setStroke(new BasicStroke(1.2f));
      gc.setColor(new Color(255, 255, 255, p.active ? 220 : 120));
      gc.draw(new Line2D.Double(x1, yEntry, x2, yEntry));

      // Stop glow + core
      gc.setStroke(new BasicStroke(8f));
      gc.setColor(new Color(C_SL.getRed(), C_SL.getGreen(), C_SL.getBlue(), p.active ? 60 : 30));
      gc.draw(new Line2D.Double(x1, yStop, x2, yStop));
      gc.setStroke(new BasicStroke(2f));
      gc.setColor(new Color(C_SL.getRed(), C_SL.getGreen(), C_SL.getBlue(), p.active ? 230 : 140));
      gc.draw(new Line2D.Double(x1, yStop, x2, yStop));

      Font font = new Font("SansSerif", Font.BOLD, 11);
      gc.setFont(font);

      String entryLab = p.isLong ? "LONG" : "SHORT";
      gc.setColor(new Color(side.getRed(), side.getGreen(), side.getBlue(), p.active ? 200 : 120));
      gc.drawString(entryLab, (int) x2 + 4, (int) yEntry - 2);

      String slLab = "SL  -1R";
      gc.setColor(new Color(C_SL.getRed(), C_SL.getGreen(), C_SL.getBlue(), p.active ? 220 : 130));
      gc.drawString(slLab, (int) x2 + 4, (int) yStop + 12);

      int dir = p.isLong ? 1 : -1;
      double prev = p.entry;
      for (int i = 0; i < p.hits.length; i++) {
        double tp = p.entry + dir * p.risk * (i + 1);
        double yTp = ctx.translateValueD(tp);
        double half = p.risk * zonePct;
        double yTopZ = ctx.translateValueD(tp + half);
        double yBotZ = ctx.translateValueD(tp - half);
        double zt = Math.min(yTopZ, yBotZ);
        double zh = Math.abs(yTopZ - yBotZ);

        boolean hit = p.hits[i];
        int zoneA = hit ? 40 : (p.active ? 22 : 12);
        gc.setColor(new Color(C_TP.getRed(), C_TP.getGreen(), C_TP.getBlue(), zoneA));
        gc.fill(new Rectangle2D.Double(x1, zt, x2 - x1, Math.max(1, zh)));

        // band between previous and target
        double yPrev = ctx.translateValueD(prev);
        double bt = Math.min(yPrev, yTp);
        double bh = Math.abs(yPrev - yTp);
        gc.setColor(new Color(C_TP.getRed(), C_TP.getGreen(), C_TP.getBlue(), p.active ? 14 : 8));
        gc.fill(new Rectangle2D.Double(x1, bt, x2 - x1, Math.max(1, bh)));

        gc.setStroke(new BasicStroke(hit ? 2.0f : 1.0f));
        gc.setColor(new Color(C_TP.getRed(), C_TP.getGreen(), C_TP.getBlue(),
            hit ? 220 : (p.active ? 160 : 90)));
        gc.draw(new Line2D.Double(x1, yTp, x2, yTp));

        String tLab = "T" + (i + 1) + "  " + (i + 1) + "R" + (hit ? "  ✓" : "");
        gc.setColor(new Color(C_TP.getRed(), C_TP.getGreen(), C_TP.getBlue(),
            hit ? 230 : (p.active ? 170 : 100)));
        gc.drawString(tLab, (int) x2 + 4, (int) yTp + 4);
        prev = tp;
      }
    }
  }

  private static class StatsBoard extends Figure
  {
    private static final int PAD = 10;
    private static final int ROW_H = 18;
    private static final int TITLE_H = 28;
    private static final int HINT_H = 22;
    private static final int COL_LABEL = 118;
    private static final int COL_VALUE = 148;
    private static final int WIDTH = PAD * 2 + COL_LABEL + COL_VALUE;

    private final String title;
    private final Color titleBg;
    private final List<HudRow> rows;
    private final String hint;

    StatsBoard(String title, Color titleBg, List<HudRow> rows, String hint)
    {
      this.title = title == null ? "" : title;
      this.titleBg = titleBg == null ? C_HUD : titleBg;
      this.rows = rows == null ? new ArrayList<>() : rows;
      this.hint = hint == null ? "" : hint;
    }

    @Override public boolean isVisible(DrawContext ctx) { return true; }

    @Override
    public void layout(DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      int h = TITLE_H + rows.size() * ROW_H + PAD * 2 + (Util.isEmpty(hint) ? 0 : HINT_H + 4);
      setBounds(new Rectangle2D.Double(gb.getMaxX() - WIDTH - 8, gb.getY() + 8, WIDTH, h));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      int h = TITLE_H + rows.size() * ROW_H + PAD * 2 + (Util.isEmpty(hint) ? 0 : HINT_H + 4);
      int bx = (int) gb.getMaxX() - WIDTH - 8;
      int by = (int) gb.getY() + 8;

      gc.setColor(new Color(14, 16, 22, 235));
      gc.fillRoundRect(bx, by, WIDTH, h, 8, 8);
      gc.setColor(new Color(70, 78, 92));
      gc.drawRoundRect(bx, by, WIDTH, h, 8, 8);

      gc.setColor(titleBg);
      gc.fillRoundRect(bx + 1, by + 1, WIDTH - 2, TITLE_H, 7, 7);
      gc.fillRect(bx + 1, by + TITLE_H - 6, WIDTH - 2, 6);
      gc.setFont(new Font("SansSerif", Font.BOLD, 12));
      gc.setColor(Color.WHITE);
      gc.drawString(title, bx + PAD, by + 18);

      Font sectionFont = new Font("SansSerif", Font.BOLD, 10);
      Font labelFont = new Font("SansSerif", Font.PLAIN, 11);
      Font valueFont = new Font("SansSerif", Font.BOLD, 11);
      int y = by + TITLE_H + 2;
      boolean alt = false;
      for (HudRow r : rows) {
        if (r.section) {
          gc.setColor(new Color(28, 34, 44));
          gc.fillRect(bx + 2, y, WIDTH - 4, ROW_H);
          gc.setFont(sectionFont);
          gc.setColor(new Color(140, 170, 210));
          gc.drawString(r.label, bx + PAD, y + 13);
          alt = false;
        }
        else {
          if (alt) {
            gc.setColor(new Color(22, 26, 34));
            gc.fillRect(bx + 2, y, WIDTH - 4, ROW_H);
          }
          gc.setFont(labelFont);
          gc.setColor(new Color(170, 178, 190));
          gc.drawString(r.label, bx + PAD, y + 13);
          gc.setFont(valueFont);
          gc.setColor(r.accent != null ? r.accent : new Color(235, 238, 245));
          FontMetrics fm = gc.getFontMetrics(valueFont);
          String val = r.value == null ? "" : r.value;
          gc.drawString(val, bx + WIDTH - PAD - fm.stringWidth(val), y + 13);
          alt = !alt;
        }
        y += ROW_H;
      }
      if (!Util.isEmpty(hint)) {
        gc.setColor(new Color(42, 34, 18));
        gc.fillRect(bx + 2, y + 2, WIDTH - 4, HINT_H);
        gc.setFont(new Font("SansSerif", Font.PLAIN, 10));
        gc.setColor(new Color(255, 200, 110));
        String htxt = hint.length() > 42 ? hint.substring(0, 41) + "…" : hint;
        gc.drawString(htxt, bx + PAD, y + 16);
      }
    }
  }

  private static String fmt(double v)
  {
    if (Double.isNaN(v)) return "-";
    if (Math.abs(v - Math.rint(v)) < 0.05) return String.format("%.0f", v);
    return String.format("%.2f", v);
  }

  private static String safe(String s)
  {
    if (s == null) return "unknown";
    return s.length() > 80 ? s.substring(0, 80) : s;
  }
}
