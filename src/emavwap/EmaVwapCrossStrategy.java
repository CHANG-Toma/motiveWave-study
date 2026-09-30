package emavwap;

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
 * EMA9 × EMA21 cross + VWAP bias filter (MotiveWave auto-entry + backtestable).
 *
 * Entry: EMA fast crosses EMA slow; long only if close > VWAP, short only if close < VWAP.
 * VWAP: 1D Ext Session (CME flip 18:00 ET), OHLC/4, no bands.
 * Exit: hybrid ATR+swing initial stop; soft BE at +R; optional ATR trail (OFF by default).
 * Session windows: London and/or NY; optional flatten before NY / session end.
 */
@StudyHeader(
    namespace = "com.emavwap.cross",
    id = "EMA_VWAP_CROSS",
    name = "EMA9x21 + VWAP Filter",
    label = "EMA9×21+VWAP",
    desc = "EMA9×EMA21 cross filtered by VWAP bias + hybrid stop. London/NY sessions.",
    menu = "EMA VWAP",
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
public class EmaVwapCrossStrategy extends Study
{
  static final String EMA_PERIOD = "emaPeriod";
  static final String EMA_SLOW_PERIOD = "emaSlowPeriod";
  static final String DIRECTION = "direction";
  static final String CONTRACTS = "contracts";
  static final String MIN_BARS_BETWEEN = "minBarsBetween";
  static final String AUTO_BACKTEST = "autoBacktest";

  static final String TRADE_LONDON = "tradeLondon";
  static final String TRADE_NY = "tradeNy";
  static final String EXIT_BEFORE_NY = "exitBeforeNy";
  static final String EXIT_SESSION_END = "exitSessionEnd";

  static final String ATR_PERIOD = "atrPeriod";
  static final String ATR_STOP_MULT = "atrStopMult";
  static final String STOP_MODE = "stopMode";
  static final String SWING_LOOKBACK = "swingLookback";
  static final String STOP_BUFFER_ATR = "stopBufferAtr";
  static final String USE_BE = "useBe";
  static final String BE_R = "beR";
  static final String BE_LOCK_R = "beLockR";
  static final String USE_TRAIL = "useTrail";
  static final String ATR_TRAIL_MULT = "atrTrailMult";

  static final String SHOW_HUD = "showHud";
  static final String SHOW_MARKERS = "showMarkers";
  static final String SHOW_STOP = "showStop";

  static final String EMA_PATH = "emaPath";
  static final String EMA_SLOW_PATH = "emaSlowPath";
  static final String VWAP_PATH = "vwapPath";
  static final String STOP_PATH = "stopPath";

  enum Values { EMA, EMA_SLOW, VWAP, ATR, SIGNAL }

  static final int SIG_NONE = 0;
  static final int SIG_LONG = 1;
  static final int SIG_SHORT = -1;

  static final ZoneId NY = ZoneId.of("America/New_York");
  static final ZoneId LON = ZoneId.of("Europe/London");
  /** London cash approx (local London time). */
  static final LocalTime LON_OPEN = LocalTime.of(8, 0);
  static final LocalTime LON_CLOSE = LocalTime.of(16, 30);
  /** NY RTH (America/New_York). */
  static final LocalTime NY_OPEN = LocalTime.of(9, 30);
  static final LocalTime NY_CLOSE = LocalTime.of(16, 0);

  static final Color C_EMA = new Color(52, 152, 219);
  static final Color C_EMA_SLOW = new Color(155, 89, 182);
  static final Color C_VWAP = new Color(241, 196, 15);
  static final Color C_STOP = new Color(231, 76, 60);
  static final Color C_HUD = new Color(20, 22, 28);
  static final Color C_LONG = new Color(25, 120, 65);
  static final Color C_SHORT = new Color(170, 35, 35);
  static final Color C_PROFIT = new Color(20, 110, 55);
  static final Color C_LOSS = new Color(140, 30, 30);
  static final String BUILD = "v2026-09-29p";

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
    final double pnlPct;
    final double pnlR;
    final String reason;

    ClosedTrade(boolean isLong, double entry, double exit, double risk, String reason)
    {
      this.isLong = isLong;
      this.entry = entry;
      this.exit = exit;
      this.reason = reason;
      this.pnlPct = isLong
          ? ((exit - entry) / entry) * 100.0
          : ((entry - exit) / entry) * 100.0;
      this.pnlR = risk > 0
          ? (isLong ? (exit - entry) / risk : (entry - exit) / risk)
          : 0;
    }
  }

  /** Live order markers (Activate / Strategy Analyzer). */
  private final List<TradeMark> tradeMarks = new ArrayList<>();
  /** Auto backtest markers (historical walk). */
  private final List<TradeMark> simMarks = new ArrayList<>();
  /** Live closed trades. */
  private final List<ClosedTrade> closedTrades = new ArrayList<>();
  /** Auto backtest closed trades. */
  private final List<ClosedTrade> simTrades = new ArrayList<>();

  private volatile boolean strategyArmed;
  private volatile boolean wasLong;
  private volatile double entryPrice;
  private volatile double stopPrice;
  private volatile double riskPerUnit;
  private volatile double extremeSinceEntry;
  private volatile boolean movedToBe;
  private volatile boolean trailArmed;
  private volatile String exitReason = "";
  private volatile int barsSinceEntry;
  private volatile int lastEntryIndex = -999;
  private volatile int lastSimKey = Integer.MIN_VALUE;
  private volatile String simVerdict = "";

  private volatile String hudTitle = "EMA9×21+VWAP";
  private volatile Color hudBadgeColor = C_HUD;
  private volatile String hudHint = "";
  private final List<HudRow> hudRows = new ArrayList<>();

  @Override
  public void initialize(Defaults defaults)
  {
    var sd = createSD();
    var tab = sd.addTab("Strategy");
    var grp = tab.addGroup("Signals");
    grp.addRow(new IntegerDescriptor(EMA_PERIOD, "EMA fast (9)", 9, 2, 200, 1));
    grp.addRow(new IntegerDescriptor(EMA_SLOW_PERIOD, "EMA slow (21)", 21, 2, 300, 1));
    grp.addRow(new DiscreteDescriptor(DIRECTION, "Direction", "BOTH", Arrays.asList(
        new NVP("Long + Short", "BOTH"),
        new NVP("Long only", "LONG"),
        new NVP("Short only", "SHORT"))));
    grp.addRow(new IntegerDescriptor(MIN_BARS_BETWEEN, "Min bars between entries", 1, 0, 50, 1));

    grp = tab.addGroup("Risk / Exits");
    grp.addRow(new IntegerDescriptor(ATR_PERIOD, "ATR Period", 14, 5, 50, 1));
    grp.addRow(new DiscreteDescriptor(STOP_MODE, "Initial stop mode", "HYBRID", Arrays.asList(
        new NVP("Hybrid (max ATR + swing)", "HYBRID"),
        new NVP("ATR only", "ATR"),
        new NVP("Swing only", "SWING"))));
    grp.addRow(new DoubleDescriptor(ATR_STOP_MULT, "Initial stop ATR mult", 2.0, 0.5, 6.0, 0.1));
    grp.addRow(new IntegerDescriptor(SWING_LOOKBACK, "Swing lookback (bars)", 5, 2, 30, 1));
    grp.addRow(new DoubleDescriptor(STOP_BUFFER_ATR, "Swing buffer (ATR)", 0.1, 0.0, 1.0, 0.05));
    grp.addRow(new BooleanDescriptor(USE_BE, "Move stop to soft BE at +R", true));
    grp.addRow(new DoubleDescriptor(BE_R, "BE trigger (+R)", 1.0, 0.5, 5.0, 0.1));
    grp.addRow(new DoubleDescriptor(BE_LOCK_R, "BE lock (+R past entry)", 0.15, 0.0, 1.0, 0.05));
    grp.addRow(new BooleanDescriptor(USE_TRAIL, "ATR trailing stop (after BE)", false));
    grp.addRow(new DoubleDescriptor(ATR_TRAIL_MULT, "Trail ATR mult", 2.0, 0.5, 6.0, 0.1));
    grp.addRow(new IntegerDescriptor(CONTRACTS, "Contracts / Shares", 1, 1, 500, 1));
    grp.addRow(new BooleanDescriptor(AUTO_BACKTEST, "Auto backtest on chart history", true));

    tab = sd.addTab("Sessions");
    grp = tab.addGroup("Trade windows");
    grp.addRow(new BooleanDescriptor(TRADE_LONDON, "Trade London (08:00–16:30 London time)", true));
    grp.addRow(new BooleanDescriptor(TRADE_NY, "Trade New York RTH (09:30–16:00 ET)", false));
    grp.addRow(new BooleanDescriptor(EXIT_BEFORE_NY, "Flatten before NY open (protect earlier trades)", true));
    grp.addRow(new BooleanDescriptor(EXIT_SESSION_END, "Flatten at end of allowed session", true));

    tab = sd.addTab("Display");
    grp = tab.addGroup("Overlay");
    grp.addRow(new BooleanDescriptor(SHOW_HUD, "Show HUD + stats", true));
    grp.addRow(new BooleanDescriptor(SHOW_MARKERS, "Show entry/exit markers", true));
    grp.addRow(new BooleanDescriptor(SHOW_STOP, "Show stop line", true));
    grp.addRow(new PathDescriptor(EMA_PATH, "EMA fast", C_EMA, 2.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(EMA_SLOW_PATH, "EMA slow", C_EMA_SLOW, 1.5f, null, true, false, true));
    grp.addRow(new PathDescriptor(VWAP_PATH, "VWAP (1D, Ext Session)", C_VWAP, 2.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(STOP_PATH, "Stop", C_STOP, 1.0f, new float[] { 6f, 4f }, true, false, true));

    var rd = createRD();
    rd.declarePath(Values.EMA, EMA_PATH);
    rd.declarePath(Values.EMA_SLOW, EMA_SLOW_PATH);
    rd.declarePath(Values.VWAP, VWAP_PATH);
    rd.setLabelPrefix("EMA9×21+VWAP");
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
    lastSimKey = Integer.MIN_VALUE;
    simVerdict = "";
    hudBadgeColor = C_HUD;
    hudTitle = "EMA9×21+VWAP  [" + BUILD + "]";
    hudHint = "Auto-backtest: retire/re-ajoute l'etude si le tableau reste vide";
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

    int fast = Math.max(2, getSettings().getInteger(EMA_PERIOD, 9));
    int slow = Math.max(fast + 1, getSettings().getInteger(EMA_SLOW_PERIOD, 21));
    Double emaFast = series.ema(index, fast, Enums.BarInput.CLOSE);
    if (emaFast != null) series.setDouble(index, Values.EMA, emaFast);
    Double emaSlow = series.ema(index, slow, Enums.BarInput.CLOSE);
    if (emaSlow != null) series.setDouble(index, Values.EMA_SLOW, emaSlow);

    int atrPeriod = Math.max(5, getSettings().getInteger(ATR_PERIOD, 14));
    Double atr = series.atr(index, atrPeriod);
    if (atr != null) series.setDouble(index, Values.ATR, atr);

    computeSessionVwap(index, series);

    int signal = SIG_NONE;
    if (index >= slow) {
      signal = emaCrossVwapSignal(
          series.getDouble(index, Values.EMA),
          series.getDouble(index - 1, Values.EMA),
          series.getDouble(index, Values.EMA_SLOW),
          series.getDouble(index - 1, Values.EMA_SLOW),
          series.getDouble(index, Values.VWAP),
          series.getClose(index));
    }
    series.setInt(index, Values.SIGNAL, signal);
    series.setComplete(index);

    // Run historical sim once indicators are filled for the full series
    if (index >= series.size() - 1) {
      if (getSettings().getBoolean(AUTO_BACKTEST, true)) {
        runHistoricalBacktest(series);
      }
      refreshHud(series, index, 0);
      drawOverlay(ctx);
    }
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
      hudBadgeColor = C_LONG;
      hudTitle = "ARMED  [" + BUILD + "]"; hudHint = "Waiting for bars";
      notifyRedraw();
    }
  }

  @Override
  public void onDeactivate(OrderContext ctx)
  {
    strategyArmed = false;
    hudBadgeColor = C_HUD;
    hudTitle = "OFF  [" + BUILD + "]";
    hudHint = "Strategy deactivated — no new entries";
    DataContext dc = ctx.getDataContext();
    if (dc != null) drawOverlay(dc);
    else notifyRedraw();
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
  public void onBarClose(OrderContext ctx)
  {
    DataContext dc = ctx.getDataContext();
    if (dc == null) return;
    DataSeries series = dc.getDataSeries();
    if (series == null || series.size() < 10) return;

    int index = series.size() - 1;
    if (!series.isBarComplete(index)) index--;
    if (index < 5) return;

    ZonedDateTime barZ = zdt(series.getStartTime(index));
    boolean inSession = isEntryAllowed(barZ);

    if (ctx.getPosition() != 0) {
      barsSinceEntry++;
      manageOpenPosition(ctx, series, index);
    }

    if (ctx.getPosition() == 0 && inSession && strategyArmed) {
      tryEnter(ctx, series, index);
    }

    refreshHud(series, index, ctx.getPosition());
    drawOverlay(dc);
  }

  @Override
  public void onPositionClosed(OrderContext ctx)
  {
    // Stats already recorded in flatten(); keep state clean if MW closes externally
    if (entryPrice > 0 && !Util.isEmpty(exitReason)) {
      // already recorded
    }
    resetTradeState();
    DataContext dc = ctx.getDataContext();
    if (dc != null && dc.getDataSeries() != null) {
      DataSeries s = dc.getDataSeries();
      refreshHud(s, Math.max(0, s.size() - 1), 0);
      drawOverlay(dc);
    }
  }

  // ---------- Auto historical backtest ----------

  /**
   * Walks every bar with the same entry/exit rules (no orders).
   * Fills simTrades + simMarks and a rentability verdict for the HUD.
   */
  private void runHistoricalBacktest(DataSeries series)
  {
    if (series == null || series.size() < 10) {
      simVerdict = "Historique trop court (<10 barres)";
      return;
    }
    int size = series.size();
    int end = series.isBarComplete(size - 1) ? size - 1 : size - 2;
    if (end < 8) {
      simVerdict = "Pas assez de barres complètes pour simuler";
      return;
    }

    int fast = Math.max(2, getSettings().getInteger(EMA_PERIOD, 9));
    int slow = Math.max(fast + 1, getSettings().getInteger(EMA_SLOW_PERIOD, 21));
    int atrPeriod = Math.max(5, getSettings().getInteger(ATR_PERIOD, 14));
    String dir = directionMode();
    int minGap = getSettings().getInteger(MIN_BARS_BETWEEN, 1);
    double trailMult = getSettings().getDouble(ATR_TRAIL_MULT, 2.0);
    double beR = getSettings().getDouble(BE_R, 1.0);
    double beLockR = Math.max(0, getSettings().getDouble(BE_LOCK_R, 0.15));
    boolean useBe = getSettings().getBoolean(USE_BE, true);
    boolean useTrail = getSettings().getBoolean(USE_TRAIL, false);
    boolean tradeLon = getSettings().getBoolean(TRADE_LONDON, true);
    boolean tradeNy = getSettings().getBoolean(TRADE_NY, false);
    boolean exitBeforeNy = getSettings().getBoolean(EXIT_BEFORE_NY, true);
    boolean exitSessEnd = getSettings().getBoolean(EXIT_SESSION_END, true);

    // Always rebuild — no empty-result cache (was wiping trades after hot-reload)
    lastSimKey = Integer.MIN_VALUE;

    // Phase 1: rebuild indicators on every bar (self-contained)
    for (int i = 0; i <= end; i++) {
      Double emaFast = series.ema(i, fast, Enums.BarInput.CLOSE);
      if (emaFast != null) series.setDouble(i, Values.EMA, emaFast);
      Double emaSlow = series.ema(i, slow, Enums.BarInput.CLOSE);
      if (emaSlow != null) series.setDouble(i, Values.EMA_SLOW, emaSlow);
      Double atr = series.atr(i, atrPeriod);
      if (atr != null) series.setDouble(i, Values.ATR, atr);
      computeSessionVwap(i, series);
    }

    // Count session bars; if filter matches nothing (wrong TF / timestamps), fall back to weekdays
    int sessionBars = 0;
    for (int i = 0; i <= end; i++) {
      if (isEntryAllowed(zdt(series.getStartTime(i)))) sessionBars++;
    }
    boolean sessionFallback = (tradeLon || tradeNy) && sessionBars < 5;
    String sessionNote = sessionFallback
        ? " | WARN session→weekdays (0 match LON/NY sur ce TF)"
        : "";

    synchronized (simTrades) { simTrades.clear(); }
    synchronized (simMarks) { simMarks.clear(); }

    boolean inPos = false;
    boolean longPos = false;
    double entry = 0;
    double stop = Double.NaN;
    double risk = 0;
    double extreme = Double.NaN;
    boolean atBe = false;
    boolean trailing = false;
    int barsHeld = 0;
    int lastIn = -999;
    int signalsSeen = 0;

    int start = Math.max(slow, atrPeriod);
    for (int i = start; i <= end; i++) {
      ZonedDateTime barZ = zdt(series.getStartTime(i));
      boolean inSession = sessionFallback ? isWeekday(barZ) : isEntryAllowed(barZ);

      double close = series.getClose(i);
      double low = series.getLow(i);
      double high = series.getHigh(i);
      long t = series.getStartTime(i);

      Double atr = series.getDouble(i, Values.ATR);

      int sig = emaCrossVwapSignal(
          series.getDouble(i, Values.EMA),
          i > 0 ? series.getDouble(i - 1, Values.EMA) : null,
          series.getDouble(i, Values.EMA_SLOW),
          i > 0 ? series.getDouble(i - 1, Values.EMA_SLOW) : null,
          series.getDouble(i, Values.VWAP),
          close);
      series.setInt(i, Values.SIGNAL, sig);
      if (sig != SIG_NONE) signalsSeen++;

      if (inPos) {
        barsHeld++;
        if (longPos) extreme = Double.isNaN(extreme) ? high : Math.max(extreme, high);
        else extreme = Double.isNaN(extreme) ? low : Math.min(extreme, low);

        if (atr != null && atr > 0 && risk > 0 && barsHeld > 0) {
          double favor = longPos ? (close - entry) : (entry - close);
          if (useBe && !atBe && favor >= beR * risk) {
            stop = softBeStop(longPos, entry, risk, beLockR);
            atBe = true;
          }
          if (useTrail && atBe) {
            if (longPos) {
              double cand = extreme - trailMult * atr;
              if (cand > stop) {
                stop = cand;
                trailing = true;
              }
            }
            else {
              double cand = extreme + trailMult * atr;
              if (cand < stop) {
                stop = cand;
                trailing = true;
              }
            }
          }
        }

        String reason = null;
        double exitPx = close;

        ZonedDateTime nextZ = (i < end) ? zdt(series.getStartTime(i + 1)) : null;
        if (!sessionFallback && exitBeforeNy && shouldFlattenBeforeNy(barZ, nextZ)) {
          reason = "PRE_NY";
          exitPx = close;
        }
        else if (!sessionFallback && exitSessEnd && shouldFlattenSessionEnd(barZ, nextZ)) {
          reason = "SESS";
          exitPx = close;
        }
        else if (sessionFallback && nextZ != null && isWeekday(barZ) && !isWeekday(nextZ)) {
          reason = "SESS";
          exitPx = close;
        }

        if (reason == null && !Double.isNaN(stop)) {
          if (longPos && low <= stop) {
            reason = stopReason(longPos, stop, entry, atBe, trailing);
            exitPx = Math.min(close, stop);
          }
          else if (!longPos && high >= stop) {
            reason = stopReason(longPos, stop, entry, atBe, trailing);
            exitPx = Math.max(close, stop);
          }
        }

        if (reason != null) {
          ClosedTrade ct = new ClosedTrade(longPos, entry, exitPx, risk, reason);
          synchronized (simTrades) { simTrades.add(ct); }
          addSimMark(t, exitPx, longPos, false, "OUT " + reason);
          inPos = false;
          entry = 0;
          stop = Double.NaN;
          risk = 0;
          extreme = Double.NaN;
          atBe = false;
          trailing = false;
          barsHeld = 0;
        }
      }

      if (!inPos && inSession && sig != SIG_NONE && (i - lastIn) >= minGap
          && atr != null && atr > 0) {
        if (sig == SIG_LONG && !"SHORT".equals(dir)) {
          inPos = true;
          longPos = true;
          entry = close;
          risk = computeInitialRisk(true, entry, atr, i, series);
          stop = entry - risk;
          extreme = high;
          atBe = false;
          trailing = false;
          barsHeld = 0;
          lastIn = i;
          addSimMark(t, entry, true, true, "IN LONG");
        }
        else if (sig == SIG_SHORT && !"LONG".equals(dir)) {
          inPos = true;
          longPos = false;
          entry = close;
          risk = computeInitialRisk(false, entry, atr, i, series);
          stop = entry + risk;
          extreme = low;
          atBe = false;
          trailing = false;
          barsHeld = 0;
          lastIn = i;
          addSimMark(t, entry, false, true, "IN SHORT");
        }
      }
    }

    if (inPos && entry > 0) {
      double exitPx = series.getClose(end);
      ClosedTrade ct = new ClosedTrade(longPos, entry, exitPx, risk, "EOD");
      synchronized (simTrades) { simTrades.add(ct); }
      addSimMark(series.getStartTime(end), exitPx, longPos, false, "OUT EOD");
    }

    Stats sn = computeStats(simTrades);
    if (sn.trades == 0) {
      if (!tradeLon && !tradeNy) {
        simVerdict = "0 trade — active LON et/ou NY (Sessions)";
      }
      else if (signalsSeen == 0) {
        simVerdict = "0 trade — aucun cross EMA9×21 filtré VWAP (change TF)";
      }
      else {
        simVerdict = String.format(
            "0 trade fermé — %d crosses, sessionBars=%d%s",
            signalsSeen, sessionBars, sessionNote);
      }
    }
    else if (sn.netR > 0 && sn.profitFactor >= 1.0) {
      simVerdict = String.format("RENTABLE  net %+.1fR  PF %.2f  (%d trades)%s",
          sn.netR, sn.profitFactor, sn.trades, sessionNote);
    }
    else if (sn.netR > 0) {
      simVerdict = String.format("MARGINAL  net %+.1fR  PF %.2f  (%d trades)%s",
          sn.netR, sn.profitFactor, sn.trades, sessionNote);
    }
    else {
      simVerdict = String.format("PAS RENTABLE  net %+.1fR  PF %.2f  (%d trades)%s",
          sn.netR, sn.profitFactor, sn.trades, sessionNote);
    }
  }

  private static String stopReason(boolean longPos, double stop, double entry, boolean atBe, boolean trailMoved)
  {
    // trailMoved only true after trail actually tightens past soft BE
    if (trailMoved && ((longPos && stop > entry) || (!longPos && stop < entry))) return "TRAIL";
    if (atBe) return "BE";
    return "SL";
  }

  private void addSimMark(long time, double price, boolean isLong, boolean isEntry, String label)
  {
    synchronized (simMarks) {
      simMarks.add(new TradeMark(time, price, isLong, isEntry, label));
      while (simMarks.size() > 200) simMarks.remove(0);
    }
  }

  // ---------- Indicators ----------

  /**
   * EMA fast × EMA slow cross, filtered by VWAP bias:
   * long only if close > VWAP, short only if close < VWAP.
   */
  private static int emaCrossVwapSignal(Double emaFast, Double prevFast,
      Double emaSlow, Double prevSlow, Double vwap, double close)
  {
    if (emaFast == null || prevFast == null || emaSlow == null || prevSlow == null || vwap == null) {
      return SIG_NONE;
    }
    boolean crossUp = prevFast <= prevSlow && emaFast > emaSlow;
    boolean crossDn = prevFast >= prevSlow && emaFast < emaSlow;
    if (crossUp && close > vwap) return SIG_LONG;
    if (crossDn && close < vwap) return SIG_SHORT;
    return SIG_NONE;
  }

  /**
   * VWAP(1D, false, Ext Session) — like MotiveWave built-in:
   * - Anchor 1D: resets each futures trading day (CME flip 18:00 ET)
   * - Ext Session: all hours of that day (no RTH filter)
   * - No std bands
   * - Source: (O+H+L+C)/4
   */
  private void computeSessionVwap(int index, DataSeries series)
  {
    ZonedDateTime zNy = zdt(series.getStartTime(index));
    LocalDate day = extSessionDay(zNy);

    double pv = 0;
    double vol = 0;
    for (int i = index; i >= 0; i--) {
      ZonedDateTime zi = zdt(series.getStartTime(i));
      if (!extSessionDay(zi).equals(day)) break;
      double ohlc4 = (series.getOpen(i) + series.getHigh(i) + series.getLow(i) + series.getClose(i)) / 4.0;
      double v = series.getVolumeAsFloat(i);
      if (v <= 0) v = 1;
      pv += ohlc4 * v;
      vol += v;
    }
    series.setDouble(index, Values.VWAP, vol > 0 ? pv / vol : (double) series.getClose(index));
  }

  /**
   * CME-style extended session day: bars from 18:00 ET belong to the next calendar date.
   * Matches MotiveWave VWAP Ext Session 1D on ES/NQ/GC.
   */
  private static LocalDate extSessionDay(ZonedDateTime zEt)
  {
    LocalDate d = zEt.toLocalDate();
    if (!zEt.toLocalTime().isBefore(LocalTime.of(18, 0))) {
      return d.plusDays(1);
    }
    return d;
  }

  // ---------- Entries / exits ----------

  private void tryEnter(OrderContext ctx, DataSeries series, int index)
  {
    Integer sig = series.getInt(index, Values.SIGNAL);
    if (sig == null || sig == SIG_NONE) return;

    int minGap = getSettings().getInteger(MIN_BARS_BETWEEN, 1);
    if (index - lastEntryIndex < minGap) return;

    Double atr = series.getDouble(index, Values.ATR);
    if (atr == null || atr <= 0) return;

    String dir = directionMode();
    double close = series.getClose(index);
    int qty = Math.max(1, getSettings().getInteger(CONTRACTS, 1));

    if (sig == SIG_LONG && !"SHORT".equals(dir)) {
      enterLong(ctx, close, atr, qty, series.getStartTime(index), index, series.getHigh(index), series);
    }
    else if (sig == SIG_SHORT && !"LONG".equals(dir)) {
      enterShort(ctx, close, atr, qty, series.getStartTime(index), index, series.getLow(index), series);
    }
  }

  private void enterLong(OrderContext ctx, double entry, double atr, int qty,
      long barTime, int index, double high, DataSeries series)
  {
    try {
      ctx.buy(qty);
      wasLong = true;
      entryPrice = entry;
      riskPerUnit = computeInitialRisk(true, entry, atr, index, series);
      stopPrice = entry - riskPerUnit;
      extremeSinceEntry = high;
      movedToBe = false;
      trailArmed = false;
      barsSinceEntry = 0;
      lastEntryIndex = index;
      exitReason = "";
      addTradeMark(barTime, entry, true, true, "IN LONG");
      debug("EMA9×21 LONG @ " + entry + " SL " + stopPrice + " risk " + riskPerUnit
          + " mode " + stopMode());
    }
    catch (Exception ex) {
      hudTitle = "ERR entry long"; hudHint = safe(ex.getMessage());
    }
  }

  private void enterShort(OrderContext ctx, double entry, double atr, int qty,
      long barTime, int index, double low, DataSeries series)
  {
    try {
      ctx.sell(qty);
      wasLong = false;
      entryPrice = entry;
      riskPerUnit = computeInitialRisk(false, entry, atr, index, series);
      stopPrice = entry + riskPerUnit;
      extremeSinceEntry = low;
      movedToBe = false;
      trailArmed = false;
      barsSinceEntry = 0;
      lastEntryIndex = index;
      exitReason = "";
      addTradeMark(barTime, entry, false, true, "IN SHORT");
      debug("EMA9×21 SHORT @ " + entry + " SL " + stopPrice + " risk " + riskPerUnit
          + " mode " + stopMode());
    }
    catch (Exception ex) {
      hudTitle = "ERR entry short"; hudHint = safe(ex.getMessage());
    }
  }

  private void manageOpenPosition(OrderContext ctx, DataSeries series, int index)
  {
    int pos = ctx.getPosition();
    if (pos == 0) return;

    double close = series.getClose(index);
    double low = series.getLow(index);
    double high = series.getHigh(index);
    long barTime = series.getStartTime(index);
    Double atr = series.getDouble(index, Values.ATR);
    boolean longPos = pos > 0;
    double beR = getSettings().getDouble(BE_R, 1.0);
    double beLockR = Math.max(0, getSettings().getDouble(BE_LOCK_R, 0.15));
    double trailMult = getSettings().getDouble(ATR_TRAIL_MULT, 2.0);
    boolean useBe = getSettings().getBoolean(USE_BE, true);
    boolean useTrail = getSettings().getBoolean(USE_TRAIL, false);

    if (longPos) extremeSinceEntry = Math.max(extremeSinceEntry, high);
    else extremeSinceEntry = Math.min(extremeSinceEntry, low);

    // Session exits: before NY open / end of allowed window
    ZonedDateTime z = zdt(barTime);
    ZonedDateTime nextZ = (index + 1 < series.size()) ? zdt(series.getStartTime(index + 1)) : null;
    if (getSettings().getBoolean(EXIT_BEFORE_NY, true) && shouldFlattenBeforeNy(z, nextZ)) {
      flatten(ctx, "PRE_NY", barTime, close, longPos);
      return;
    }
    if (getSettings().getBoolean(EXIT_SESSION_END, true) && shouldFlattenSessionEnd(z, nextZ)) {
      flatten(ctx, "SESS", barTime, close, longPos);
      return;
    }

    // Soft BE (+ optional ATR trail after BE)
    if (atr != null && atr > 0 && riskPerUnit > 0 && barsSinceEntry > 0) {
      double favor = longPos ? (close - entryPrice) : (entryPrice - close);
      if (useBe && !movedToBe && favor >= beR * riskPerUnit) {
        stopPrice = softBeStop(longPos, entryPrice, riskPerUnit, beLockR);
        movedToBe = true;
      }
      if (useTrail && movedToBe) {
        if (longPos) {
          double cand = extremeSinceEntry - trailMult * atr;
          if (cand > stopPrice) {
            stopPrice = cand;
            trailArmed = true;
          }
        }
        else {
          double cand = extremeSinceEntry + trailMult * atr;
          if (cand < stopPrice) {
            stopPrice = cand;
            trailArmed = true;
          }
        }
      }
    }

    // Stop hit
    if (longPos && !Double.isNaN(stopPrice) && low <= stopPrice) {
      flatten(ctx, stopReason(true, stopPrice, entryPrice, movedToBe, trailArmed),
          barTime, Math.min(close, stopPrice), true);
      return;
    }
    if (!longPos && !Double.isNaN(stopPrice) && high >= stopPrice) {
      flatten(ctx, stopReason(false, stopPrice, entryPrice, movedToBe, trailArmed),
          barTime, Math.max(close, stopPrice), false);
    }
  }

  private void flatten(OrderContext ctx, String reason, long barTime, double price, boolean wasLongPos)
  {
    try {
      ctx.closeAtMarket();
      exitReason = reason;
      if (entryPrice > 0) {
        ClosedTrade t = new ClosedTrade(wasLongPos, entryPrice, price, riskPerUnit, reason);
        synchronized (closedTrades) {
          closedTrades.add(t);
        }
      }
      addTradeMark(barTime, price, wasLongPos, false, "OUT " + reason);
      debug("EMA9×21 flatten " + reason + " @ " + price);
      resetTradeState();
    }
    catch (Exception ex) {
      hudTitle = "ERR flatten"; hudHint = safe(ex.getMessage());
    }
  }

  private void resetTradeState()
  {
    entryPrice = 0;
    stopPrice = Double.NaN;
    riskPerUnit = 0;
    extremeSinceEntry = Double.NaN;
    movedToBe = false;
    trailArmed = false;
    barsSinceEntry = 0;
    wasLong = false;
  }

  private void addTradeMark(long time, double price, boolean isLong, boolean isEntry, String label)
  {
    synchronized (tradeMarks) {
      tradeMarks.add(new TradeMark(time, price, isLong, isEntry, label));
      while (tradeMarks.size() > 60) tradeMarks.remove(0);
    }
  }

  // ---------- HUD / stats ----------

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

    static HudRow section(String title)
    {
      return new HudRow(title, "", null, true);
    }

    static HudRow kv(String label, String value)
    {
      return new HudRow(label, value, null, false);
    }

    static HudRow kv(String label, String value, Color accent)
    {
      return new HudRow(label, value, accent, false);
    }
  }

  private void refreshHud(DataSeries series, int index, int position)
  {
    if (series == null || index < 0 || index >= series.size()) return;

    double close = series.getClose(index);
    Integer sig = series.getInt(index, Values.SIGNAL);
    Stats sn = computeStats(simTrades);
    List<HudRow> rows = new ArrayList<>();

    if (sn.trades > 0) {
      boolean ok = sn.netR > 0 && sn.profitFactor >= 1.0;
      boolean mid = sn.netR > 0 && !ok;
      hudBadgeColor = ok ? C_PROFIT : (mid ? new Color(120, 90, 30) : C_LOSS);
      hudTitle = (ok ? "RENTABLE" : (mid ? "MARGINAL" : "PAS RENTABLE"))
          + "   [" + BUILD + "]";

      Color netC = sn.netR >= 0 ? new Color(120, 230, 160) : new Color(255, 120, 120);
      Color pfC = sn.profitFactor >= 1.15 ? new Color(120, 230, 160)
          : (sn.profitFactor >= 1.0 ? new Color(255, 210, 120) : new Color(255, 120, 120));

      rows.add(HudRow.section("PERFORMANCE"));
      rows.add(HudRow.kv("Net R", String.format("%+.1fR", sn.netR), netC));
      rows.add(HudRow.kv("Profit Factor", String.format("%.2f", sn.profitFactor), pfC));
      rows.add(HudRow.kv("Expectancy", String.format("%+.2fR / trade", sn.expectancy)));
      rows.add(HudRow.kv("Trades", Integer.toString(sn.trades)));
      rows.add(HudRow.kv("Win rate", String.format("%.0f%%", sn.winRate)));
      rows.add(HudRow.kv("Avg win / loss", String.format("%+.1fR  /  %+.1fR", sn.avgWin, sn.avgLoss)));
      rows.add(HudRow.kv("Payoff", String.format("%.2f", sn.payoff)));

      rows.add(HudRow.section("SIDES"));
      rows.add(HudRow.kv("Long", String.format("%d trades · %.0f%% win", sn.longs, sn.longWinRate),
          sn.longs >= 10 && sn.longWinRate + 10 < sn.shortWinRate ? new Color(255, 150, 120) : null));
      rows.add(HudRow.kv("Short", String.format("%d trades · %.0f%% win", sn.shorts, sn.shortWinRate),
          sn.shorts >= 10 && sn.shortWinRate + 10 < sn.longWinRate ? new Color(255, 150, 120) : null));

      rows.add(HudRow.section("EXITS"));
      rows.add(HudRow.kv("SL", String.format("%d  (%.0f%%)", sn.nSl, sn.pct(sn.nSl))));
      rows.add(HudRow.kv("BE", String.format("%d  (%.0f%%)", sn.nBe, sn.pct(sn.nBe))));
      rows.add(HudRow.kv("TRAIL", Integer.toString(sn.nTrail)));
      rows.add(HudRow.kv("PRE_NY", Integer.toString(sn.nPreNy)));
      rows.add(HudRow.kv("SESS", Integer.toString(sn.nSess)));
      rows.add(HudRow.kv("EOD", Integer.toString(sn.nEod)));

      rows.add(HudRow.section("SETUP"));
      rows.add(HudRow.kv("Signal", "EMA"
          + getSettings().getInteger(EMA_PERIOD, 9) + "×"
          + getSettings().getInteger(EMA_SLOW_PERIOD, 21) + " + VWAP bias"));
      rows.add(HudRow.kv("VWAP", "1D · Ext Session · no bands"));
      rows.add(HudRow.kv("Direction", directionMode()));
      rows.add(HudRow.kv("Sessions",
          (getSettings().getBoolean(TRADE_LONDON, true) ? "LON " : "")
              + (getSettings().getBoolean(TRADE_NY, false) ? "NY" : "")
              + (!getSettings().getBoolean(TRADE_LONDON, true) && !getSettings().getBoolean(TRADE_NY, false) ? "none" : "")));
      rows.add(HudRow.kv("Stop",
          stopMode() + " · " + String.format("%.1f ATR", getSettings().getDouble(ATR_STOP_MULT, 2.0))));
      rows.add(HudRow.kv("Breakeven",
          getSettings().getBoolean(USE_BE, true)
              ? ("ON @" + getSettings().getDouble(BE_R, 1.0) + "R → +"
                  + getSettings().getDouble(BE_LOCK_R, 0.15) + "R")
              : "OFF"));
      rows.add(HudRow.kv("Trail",
          getSettings().getBoolean(USE_TRAIL, false)
              ? (getSettings().getDouble(ATR_TRAIL_MULT, 2.0) + " ATR") : "OFF"));

      if (position != 0) {
        String side = position > 0 ? "LONG" : "SHORT";
        double uR = riskPerUnit > 0
            ? (position > 0 ? (close - entryPrice) : (entryPrice - close)) / riskPerUnit
            : 0;
        hudHint = String.format("LIVE %s x%d  %+.1fR  SL %s%s",
            side, Math.abs(position), uR, fmt(stopPrice), movedToBe ? "  (BE)" : "");
      }
      else if (sig != null && sig == SIG_LONG) {
        hudHint = "SIGNAL EMA cross up + above VWAP — long pret";
      }
      else if (sig != null && sig == SIG_SHORT) {
        hudHint = "SIGNAL EMA cross down + below VWAP — short pret";
      }
      else {
        hudHint = improvementHint(sn);
      }
    }
    else if (strategyArmed && position != 0) {
      hudBadgeColor = position > 0 ? C_LONG : C_SHORT;
      String side = position > 0 ? "LONG" : "SHORT";
      double uR = riskPerUnit > 0
          ? (position > 0 ? (close - entryPrice) : (entryPrice - close)) / riskPerUnit
          : 0;
      hudTitle = "IN TRADE  " + side + "  [" + BUILD + "]";
      rows.add(HudRow.section("POSITION"));
      rows.add(HudRow.kv("Side", side));
      rows.add(HudRow.kv("Qty", Integer.toString(Math.abs(position))));
      rows.add(HudRow.kv("Unrealized", String.format("%+.1fR", uR)));
      rows.add(HudRow.kv("Stop", fmt(stopPrice)));
      hudHint = movedToBe ? "Stop at soft BE" : "Initial " + stopMode() + " stop";
    }
    else {
      hudBadgeColor = strategyArmed ? C_LONG : C_HUD;
      hudTitle = (strategyArmed ? "ARMED" : "BACKTEST") + "  [" + BUILD + "]";
      rows.add(HudRow.section("STATUS"));
      rows.add(HudRow.kv("Trades", "0 sur cet historique"));
      rows.add(HudRow.kv("Diagnostic",
          Util.isEmpty(simVerdict) ? "En cours / pas de fills" : simVerdict));
      rows.add(HudRow.section("SETUP"));
      rows.add(HudRow.kv("Signal", "EMA"
          + getSettings().getInteger(EMA_PERIOD, 9) + "×"
          + getSettings().getInteger(EMA_SLOW_PERIOD, 21) + " + VWAP bias"));
      rows.add(HudRow.kv("VWAP", "1D · Ext Session · no bands"));
      rows.add(HudRow.kv("Direction", directionMode()));
      rows.add(HudRow.kv("LON / NY",
          (getSettings().getBoolean(TRADE_LONDON, true) ? "ON" : "off")
              + " / "
              + (getSettings().getBoolean(TRADE_NY, false) ? "ON" : "off")));
      rows.add(HudRow.kv("Stop / Trail",
          String.format("%s · %.1f ATR / %s",
              stopMode(),
              getSettings().getDouble(ATR_STOP_MULT, 2.0),
              getSettings().getBoolean(USE_TRAIL, false) ? "ON" : "OFF")));
      hudHint = "Retire/re-ajoute l'etude si le tableau reste vide apres reload";
    }

    synchronized (hudRows) {
      hudRows.clear();
      hudRows.addAll(rows);
    }
  }

  private static String improvementHint(Stats sn)
  {
    if (sn.trades < 30) {
      return "HINT: peu de trades (<30) — charge plus d'historique";
    }
    if (sn.nSl > sn.trades * 0.45) {
      return "HINT: beaucoup de SL — HYBRID/swing ou ATR 2.5";
    }
    if (sn.payoff < 1.2 && sn.winRate < 45) {
      return "HINT: payoff faible — trail off / BE plus tard";
    }
    if (sn.longs >= 10 && sn.shorts >= 10
        && Math.abs(sn.longWinRate - sn.shortWinRate) >= 10) {
      return sn.longWinRate < sn.shortWinRate
          ? "HINT: longs faibles — teste Short only"
          : "HINT: shorts faibles — teste Long only";
    }
    if (sn.profitFactor > 0 && sn.profitFactor < 1.15 && sn.netR > 0) {
      return "HINT: PF juste OK — surveille frais live";
    }
    if (sn.netR > 0 && sn.profitFactor >= 1.15) {
      return "HINT: edge OK — valide out-of-sample";
    }
    return "HINT: change 1 parametre a la fois";
  }

  private static class Stats
  {
    int trades, longs, shorts, wins, longWins, shortWins;
    int nSl, nBe, nTrail, nPreNy, nSess, nEod;
    double netR, avgWin, avgLoss, winRate, longWinRate, shortWinRate, profitFactor;
    double expectancy, payoff;
    String lastReason = "-";

    double pct(int n)
    {
      return trades > 0 ? (100.0 * n / trades) : 0;
    }
  }

  private Stats computeStats(List<ClosedTrade> source)
  {
    Stats s = new Stats();
    List<ClosedTrade> copy;
    synchronized (source) {
      copy = new ArrayList<>(source);
    }
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
      s.lastReason = t.reason;
      if ("SL".equals(t.reason)) s.nSl++;
      else if ("BE".equals(t.reason)) s.nBe++;
      else if ("TRAIL".equals(t.reason)) s.nTrail++;
      else if ("PRE_NY".equals(t.reason)) s.nPreNy++;
      else if ("SESS".equals(t.reason)) s.nSess++;
      else if ("EOD".equals(t.reason)) s.nEod++;
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

  private void drawOverlay(DataContext ctx)
  {
    beginFigureUpdate();
    clearFigures();

    if (getSettings().getBoolean(SHOW_STOP, true) && !Double.isNaN(stopPrice) && entryPrice > 0) {
      addFigure(new LevelLine(stopPrice, C_STOP, 1.0f, "SL " + fmt(stopPrice), true));
    }

    if (getSettings().getBoolean(SHOW_MARKERS, true)) {
      // Prefer auto-backtest marks; fall back to live marks
      List<TradeMark> marks;
      synchronized (simMarks) {
        marks = new ArrayList<>(simMarks);
      }
      if (marks.isEmpty()) {
        synchronized (tradeMarks) {
          marks = new ArrayList<>(tradeMarks);
        }
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
      List<HudRow> rows;
      synchronized (hudRows) {
        rows = new ArrayList<>(hudRows);
      }
      addFigure(new StatsBoard(hudTitle, hudBadgeColor, rows, hudHint));
    }

    endFigureUpdate();
    notifyRedraw();
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

  private static class StatsBoard extends Figure
  {
    private static final int PAD = 10;
    private static final int ROW_H = 18;
    private static final int TITLE_H = 28;
    private static final int HINT_H = 22;
    private static final int COL_LABEL = 110;
    private static final int COL_VALUE = 150;
    private static final int WIDTH = PAD * 2 + COL_LABEL + COL_VALUE;

    private final String title;
    private final Color titleBg;
    private final List<HudRow> rows;
    private final String hint;

    StatsBoard(String title, Color titleBg, List<HudRow> rows, String hint)
    {
      this.title = title == null ? "" : title;
      this.titleBg = titleBg == null ? new Color(20, 22, 28) : titleBg;
      this.rows = rows == null ? new ArrayList<>() : rows;
      this.hint = hint == null ? "" : hint;
    }

    @Override
    public boolean isVisible(DrawContext ctx) { return true; }

    @Override
    public void layout(DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      int h = TITLE_H + rows.size() * ROW_H + PAD * 2
          + (Util.isEmpty(hint) ? 0 : HINT_H + 4);
      int bx = (int) gb.getMaxX() - WIDTH - 8;
      int by = (int) gb.getY() + 8;
      setBounds(new Rectangle2D.Double(bx, by, WIDTH, h));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      int h = TITLE_H + rows.size() * ROW_H + PAD * 2
          + (Util.isEmpty(hint) ? 0 : HINT_H + 4);
      int bx = (int) gb.getMaxX() - WIDTH - 8;
      int by = (int) gb.getY() + 8;
      setBounds(new Rectangle2D.Double(bx, by, WIDTH, h));

      // Panel background
      gc.setColor(new Color(14, 16, 22, 235));
      gc.fillRoundRect(bx, by, WIDTH, h, 8, 8);
      gc.setColor(new Color(70, 78, 92));
      gc.drawRoundRect(bx, by, WIDTH, h, 8, 8);

      // Title bar
      gc.setColor(titleBg);
      gc.fillRoundRect(bx + 1, by + 1, WIDTH - 2, TITLE_H, 7, 7);
      gc.fillRect(bx + 1, by + TITLE_H - 6, WIDTH - 2, 6);
      Font titleFont = new Font("SansSerif", Font.BOLD, 12);
      gc.setFont(titleFont);
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
          int vw = fm.stringWidth(r.value == null ? "" : r.value);
          gc.drawString(r.value == null ? "" : r.value, bx + WIDTH - PAD - vw, y + 13);
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

  // ---------- Helpers ----------

  private String directionMode()
  {
    String m = getSettings().getString(DIRECTION);
    return Util.isEmpty(m) ? "BOTH" : m;
  }

  private String stopMode()
  {
    String m = getSettings().getString(STOP_MODE);
    return Util.isEmpty(m) ? "HYBRID" : m;
  }

  /**
   * Initial risk distance for stop placement.
   * ATR: stopMult × ATR
   * SWING: entry → swing extreme ± buffer ATR
   * HYBRID: max of both (wider / less noise)
   */
  private double computeInitialRisk(boolean isLong, double entry, double atr, int index, DataSeries series)
  {
    String mode = stopMode();
    double stopMult = getSettings().getDouble(ATR_STOP_MULT, 2.0);
    double atrRisk = Math.max(1e-9, stopMult * atr);
    if ("ATR".equals(mode)) return atrRisk;

    int lookback = Math.max(2, getSettings().getInteger(SWING_LOOKBACK, 5));
    double buffer = Math.max(0, getSettings().getDouble(STOP_BUFFER_ATR, 0.1)) * atr;
    int from = Math.max(0, index - lookback + 1);

    double swingRisk;
    if (isLong) {
      double swingLow = series.getLow(from);
      for (int i = from + 1; i <= index; i++) {
        swingLow = Math.min(swingLow, series.getLow(i));
      }
      swingRisk = Math.max(1e-9, entry - (swingLow - buffer));
    }
    else {
      double swingHigh = series.getHigh(from);
      for (int i = from + 1; i <= index; i++) {
        swingHigh = Math.max(swingHigh, series.getHigh(i));
      }
      swingRisk = Math.max(1e-9, (swingHigh + buffer) - entry);
    }

    if ("SWING".equals(mode)) return swingRisk;
    return Math.max(atrRisk, swingRisk);
  }

  /** Soft BE: lock a fraction of R past entry so exact-entry wicks don't stop out. */
  private static double softBeStop(boolean isLong, double entry, double risk, double beLockR)
  {
    double lock = Math.max(0, beLockR) * Math.max(0, risk);
    return isLong ? entry + lock : entry - lock;
  }

  private static ZonedDateTime zdt(long millis)
  {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), NY);
  }

  private static ZonedDateTime zLon(ZonedDateTime zNy)
  {
    return zNy.withZoneSameInstant(LON);
  }

  private static boolean isWeekday(ZonedDateTime z)
  {
    return z.getDayOfWeek().getValue() <= 5;
  }

  /** London cash window in Europe/London local time. */
  private static boolean isLondon(ZonedDateTime zNy)
  {
    ZonedDateTime z = zLon(zNy);
    LocalTime t = z.toLocalTime();
    return isWeekday(z) && !t.isBefore(LON_OPEN) && t.isBefore(LON_CLOSE);
  }

  /** NY RTH in America/New_York. */
  private static boolean isNyRth(ZonedDateTime zNy)
  {
    LocalTime t = zNy.toLocalTime();
    return isWeekday(zNy) && !t.isBefore(NY_OPEN) && t.isBefore(NY_CLOSE);
  }

  /** New entries allowed if inside any enabled session. If both off → no entries. */
  private boolean isEntryAllowed(ZonedDateTime zNy)
  {
    boolean lon = getSettings().getBoolean(TRADE_LONDON, true);
    boolean ny = getSettings().getBoolean(TRADE_NY, false);
    if (!lon && !ny) return false;
    return (lon && isLondon(zNy)) || (ny && isNyRth(zNy));
  }

  /**
   * Flatten on the last bar before NY cash open (09:30 ET), to avoid open-drive risk
   * on a trade taken earlier (e.g. London).
   */
  private boolean shouldFlattenBeforeNy(ZonedDateTime barZ, ZonedDateTime nextZ)
  {
    if (!isWeekday(barZ)) return false;
    LocalTime t = barZ.toLocalTime();
    if (!t.isBefore(NY_OPEN)) return false;
    if (nextZ == null) return false;
    // Next bar crosses into NY open same ET day
    return nextZ.toLocalDate().equals(barZ.toLocalDate())
        && !nextZ.toLocalTime().isBefore(NY_OPEN);
  }

  /** Flatten when leaving the union of enabled sessions (last bar still inside). */
  private boolean shouldFlattenSessionEnd(ZonedDateTime barZ, ZonedDateTime nextZ)
  {
    if (!isEntryAllowed(barZ)) return false;
    if (nextZ == null) return true;
    return !isEntryAllowed(nextZ);
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
