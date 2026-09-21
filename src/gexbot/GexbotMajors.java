package gexbot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.NVP;
import com.motivewave.platform.sdk.common.PathInfo;
import com.motivewave.platform.sdk.common.Util;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.DiscreteDescriptor;
import com.motivewave.platform.sdk.common.desc.DoubleDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.common.desc.StringDescriptor;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

/**
 * Classic majors HUD inspired by Gamma Levels style:
 * - 1 regime badge
 * - 1 compact info panel
 * - clean right-side level tags
 * - optional max-change panel
 */
@StudyHeader(
    namespace = "com.gexbot.custom",
    id = "GEXBOT_MAJORS",
    name = "Gexbot Majors (Custom)",
    label = "Gexbot Majors",
    desc = "Call/Put/Zero Gamma levels + regime badge + live action plan. Requires a Custom API key.",
    menu = "Gexbot",
    overlay = true,
    studyOverlay = true)
public class GexbotMajors extends Study
{
  static final String API_KEY = "apiKey";
  static final String TICKER = "ticker";
  static final String FUTURES_TARGET = "futuresTarget";
  static final String CATEGORY = "category";
  static final String REFRESH_SEC = "refreshSec";
  static final String STRIKE_MULT = "strikeMult";
  static final String STRIKE_ADD = "strikeAdd";

  static final String ZERO_PATH = "zeroPath";
  static final String CALL_PATH = "callPath";
  static final String PUT_PATH = "putPath";
  static final String CALL_OI_PATH = "callOiPath";
  static final String PUT_OI_PATH = "putOiPath";
  static final String IV68_PATH = "iv68Path";
  static final String IV80_PATH = "iv80Path";
  static final String MX_PATH = "maxChangePath";
  static final String DUAL_ZERO_PATH = "dualZeroPath";
  static final String DUAL_CALL_PATH = "dualCallPath";
  static final String DUAL_PUT_PATH = "dualPutPath";
  static final String ATM_IV = "atmIvPct";

  static final String SHOW_HUD = "showHud";
  static final String SHOW_REGIME = "showRegime";
  static final String SHOW_STRATEGY = "showStrategy";
  static final String SHOW_LEVEL_LABELS = "showLevelLabels";
  static final String SHOW_MAX_CHANGE = "showMaxChange";
  static final String SHOW_MAX_CHANGE_LEVELS = "showMaxChangeLevels";
  static final String SHOW_DUAL_HORIZON = "showDualHorizon";
  static final String SHOW_OI_DIVERGE = "showOiDiverge";
  static final String SHOW_ERRORS = "showErrors";
  static final String HUD_POS = "hudPosition";

  static final String BASE_URL = "https://api.gex.bot/v2";
  static final Path DEBUG_LOG = Path.of(System.getProperty("user.home"), "MotiveWave Extensions", "gexbot_majors_debug.txt");

  static final Color C_CALL = new Color(46, 204, 113);
  static final Color C_PUT = new Color(231, 76, 60);
  static final Color C_CALL_OI = new Color(26, 160, 90);
  static final Color C_PUT_OI = new Color(180, 50, 45);
  static final Color C_ZG = new Color(243, 156, 18);
  static final Color C_IV68 = new Color(219, 88, 228);
  static final Color C_IV80 = new Color(176, 92, 232);
  static final Color C_MX = new Color(52, 152, 219);
  static final Color C_DUAL_ZG = new Color(241, 196, 15);
  static final Color C_DUAL_CALL = new Color(39, 174, 96);
  static final Color C_DUAL_PUT = new Color(192, 57, 43);
  static final Color C_HUD_BG = new Color(20, 22, 28);
  static final Color C_REGIME_NEG = new Color(170, 35, 35);
  static final Color C_REGIME_POS = new Color(25, 120, 65);
  static final Color C_WARN = new Color(180, 120, 30);
  /** Normal z for a two-tailed 80% interval. 68% uses 1σ. */
  static final double Z_80 = 1.2815515655446004;
  /** Strikes farther than this (source units) count as Vol vs OI divergence. */
  static final double OI_DIVERGE_PTS = 2.0;
  /** Near a wall when within this fraction of the Call–Put range (fallback: 0.35% of spot). */
  static final double NEAR_FRAC = 0.12;
  static final double NEAR_SPOT_FRAC = 0.0035;

  private final AtomicBoolean fetching = new AtomicBoolean(false);
  private volatile MajorsData lastData;
  private volatile MajorsData lastDualData;
  private volatile String lastDualCategory = "";
  private volatile MaxChangeData lastMaxChange;
  private volatile Conversion lastConversion = Conversion.identity();
  private volatile String lastTicker = "";
  private volatile String lastFuture = "";
  private volatile long lastFetchMs;
  private volatile String lastStatus = "Waiting...";
  private volatile DataContext latestCtx;
  private volatile Double lastIv;
  private volatile Double lastSigma1;

  @Override
  public void initialize(Defaults defaults)
  {
    var sd = createSD();
    var tab = sd.addTab("General");

    var grp = tab.addGroup("1) Connection");
    var keyDesc = new StringDescriptor(API_KEY, "Custom API Key", "", 60, true, false);
    keyDesc.setRequired(true);
    grp.addRow(keyDesc);

    grp = tab.addGroup("2) Symbol");
    grp.addRow(new DiscreteDescriptor(TICKER, "Source Ticker", "AUTO", Arrays.asList(
        new NVP("Auto (NQ→QQQ, ES→SPY)", "AUTO"),
        new NVP("QQQ (for NQ chart)", "QQQ"),
        new NVP("SPY (for ES chart)", "SPY"),
        new NVP("NDX", "NDX"),
        new NVP("SPX", "SPX"),
        new NVP("IWM", "IWM"),
        new NVP("NQ_NDX", "NQ_NDX"),
        new NVP("ES_SPX", "ES_SPX"))));
    grp.addRow(new DiscreteDescriptor(FUTURES_TARGET, "Futures Conversion", "AUTO", Arrays.asList(
        new NVP("Auto (QQQ→NQ, SPY→ES)", "AUTO"),
        new NVP("Force NQ", "NQ"),
        new NVP("Force ES", "ES"),
        new NVP("Force RTY", "RTY"),
        new NVP("None", "NONE"))));
    grp.addRow(new DiscreteDescriptor(CATEGORY, "GEX Period", "gex_zero", Arrays.asList(
        new NVP("0DTE", "gex_zero"),
        new NVP("90 Day", "gex_full"),
        new NVP("1DTE", "gex_one"))));

    grp = tab.addGroup("3) Advanced");
    grp.addRow(new IntegerDescriptor(REFRESH_SEC, "Refresh (sec)", 15, 5, 600, 5));
    grp.addRow(new DoubleDescriptor(STRIKE_MULT, "Strike Multiplier", 1.0, 0.01, 1000.0, 0.01));
    grp.addRow(new DoubleDescriptor(STRIKE_ADD, "Strike Offset", 0.0, -100000.0, 100000.0, 0.25));
    grp.addRow(new DoubleDescriptor(ATM_IV, "ATM IV % (0 = auto VIX/VXN)", 0.0, 0.0, 200.0, 0.1));

    tab = sd.addTab("Display");
    // Core: Call / Put / Zero + regime + diverge. Everything else off by default.
    grp = tab.addGroup("Core levels");
    grp.addRow(new PathDescriptor(ZERO_PATH, "Zero Gamma", C_ZG, 1.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(CALL_PATH, "Call Wall", C_CALL, 1.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(PUT_PATH, "Put Wall", C_PUT, 1.0f, null, true, false, true));

    grp = tab.addGroup("HUD");
    grp.addRow(new DiscreteDescriptor(HUD_POS, "HUD Position", "TR", Arrays.asList(
        new NVP("Top Right", "TR"),
        new NVP("Top Left", "TL"),
        new NVP("Bottom Right", "BR"),
        new NVP("Bottom Left", "BL"))));
    grp.addRow(new BooleanDescriptor(SHOW_REGIME, "Regime Badge (+GEX / -GEX)", true));
    grp.addRow(new BooleanDescriptor(SHOW_STRATEGY, "Action Plan (what to do)", true));
    grp.addRow(new BooleanDescriptor(SHOW_OI_DIVERGE, "Vol vs OI Diverge Flag", true));
    grp.addRow(new BooleanDescriptor(SHOW_LEVEL_LABELS, "Level Labels (right)", true));
    grp.addRow(new BooleanDescriptor(SHOW_ERRORS, "Show Errors Only", true));

    grp = tab.addGroup("Optional (off by default)");
    grp.addRow(new BooleanDescriptor(SHOW_HUD, "Compact Info Panel", false));
    grp.addRow(new BooleanDescriptor(SHOW_MAX_CHANGE, "Max Change Panel", false));
    grp.addRow(new BooleanDescriptor(SHOW_MAX_CHANGE_LEVELS, "Max Change on Chart", false));
    grp.addRow(new BooleanDescriptor(SHOW_DUAL_HORIZON, "Overlay opposite period (0DTE↔90D)", false));
    grp.addRow(new PathDescriptor(CALL_OI_PATH, "Call Wall (OI)", C_CALL_OI, 1.0f, new float[] { 4f, 4f }, false, false, true));
    grp.addRow(new PathDescriptor(PUT_OI_PATH, "Put Wall (OI)", C_PUT_OI, 1.0f, new float[] { 4f, 4f }, false, false, true));
    grp.addRow(new PathDescriptor(IV68_PATH, "IV Range (68%)", C_IV68, 1.0f, new float[] { 6f, 5f }, false, false, true));
    grp.addRow(new PathDescriptor(IV80_PATH, "IV Range (80%)", C_IV80, 1.0f, new float[] { 6f, 5f }, false, false, true));
    grp.addRow(new PathDescriptor(MX_PATH, "Max Change Strikes", C_MX, 1.0f, new float[] { 3f, 4f }, false, false, true));
    grp.addRow(new PathDescriptor(DUAL_ZERO_PATH, "Dual Zero Gamma", C_DUAL_ZG, 1.0f, new float[] { 8f, 4f }, false, false, true));
    grp.addRow(new PathDescriptor(DUAL_CALL_PATH, "Dual Call Wall", C_DUAL_CALL, 1.0f, new float[] { 8f, 4f }, false, false, true));
    grp.addRow(new PathDescriptor(DUAL_PUT_PATH, "Dual Put Wall", C_DUAL_PUT, 1.0f, new float[] { 8f, 4f }, false, false, true));

    var rd = createRD();
    rd.setLabelSettings(TICKER, FUTURES_TARGET, CATEGORY);
  }

  @Override
  public void onLoad(Defaults defaults)
  {
    lastFetchMs = 0;
    lastStatus = "Waiting...";
    lastData = null;
    lastDualData = null;
    lastDualCategory = "";
    lastMaxChange = null;
    lastConversion = Conversion.identity();
    lastIv = null;
    lastSigma1 = null;
    logDebug("onLoad");
  }

  @Override
  public void destroy()
  {
    lastData = null;
    lastDualData = null;
    lastMaxChange = null;
    latestCtx = null;
  }

  @Override
  protected void calculateValues(DataContext ctx)
  {
    latestCtx = ctx;
    drawFigures(ctx);
    maybeFetch(ctx, false);
  }

  @Override
  public void onBarUpdate(DataContext ctx)
  {
    latestCtx = ctx;
    maybeFetch(ctx, false);
  }

  @Override
  public void onBarClose(DataContext ctx)
  {
    latestCtx = ctx;
    maybeFetch(ctx, true);
  }

  private void maybeFetch(DataContext ctx, boolean force)
  {
    int refreshSec = getSettings().getInteger(REFRESH_SEC, 15);
    long now = System.currentTimeMillis();
    if (!force && lastFetchMs > 0 && now - lastFetchMs < refreshSec * 1000L) return;
    if (!fetching.compareAndSet(false, true)) return;

    final DataContext dataCtx = ctx;
    Thread t = new Thread(() -> {
      try {
        setStatus("Fetching...");
        ResolvedRequest req = resolveRequest(dataCtx);
        lastTicker = req.ticker;
        lastFuture = req.futuresTarget == null ? "" : req.futuresTarget;

        if (req.futuresTarget != null && needsConversion(req.ticker)) {
          lastConversion = fetchConversion(req.apiKey, req.ticker, req.futuresTarget);
        }
        else {
          lastConversion = Conversion.identity();
        }

        MajorsData data = fetchMajors(req);
        MajorsData dual = null;
        String dualCat = "";
        if (getSettings().getBoolean(SHOW_DUAL_HORIZON, false)) {
          dualCat = dualCategoryFor(req.category);
          if (!Util.isEmpty(dualCat)) {
            dual = fetchMajors(req.withCategory(dualCat));
          }
        }

        MaxChangeData mx = null;
        if (getSettings().getBoolean(SHOW_MAX_CHANGE, false)
            || getSettings().getBoolean(SHOW_MAX_CHANGE_LEVELS, false)) {
          mx = fetchMaxChange(req);
        }

        if (ivRangeEnabled()) {
          Double iv = fetchAtmIv(req.ticker);
          if (iv != null) lastIv = iv;
        }

        if (data != null) {
          lastData = data;
          lastDualData = dual;
          lastDualCategory = dualCat == null ? "" : dualCat;
          lastMaxChange = mx;
          lastSigma1 = computeSigma1(data, lastIv, req.category);
          lastFetchMs = System.currentTimeMillis();
          setStatus("OK");
        }
        else {
          lastFetchMs = System.currentTimeMillis() - Math.max(0, (refreshSec - 5) * 1000L);
        }
      }
      catch (Exception ex) {
        lastFetchMs = System.currentTimeMillis() - Math.max(0, (refreshSec - 5) * 1000L);
        setStatus("ERR " + safeMsg(ex.getMessage()));
        logDebug("exception: " + ex);
      }
      finally {
        fetching.set(false);
        try {
          if (dataCtx != null) recalculate(dataCtx);
        }
        catch (Exception ex) {
          try { drawFigures(dataCtx); notifyRedraw(); } catch (Exception ignored) {}
        }
      }
    }, "gexbot-majors-fetch");
    t.setDaemon(true);
    t.start();
  }

  private void drawFigures(DataContext ctx)
  {
    if (ctx == null) ctx = latestCtx;
    if (ctx == null) return;

    beginFigureUpdate();
    clearFigures();

    var series = ctx.getDataSeries();
    MajorsData data = lastData;

    if (data != null && series != null && series.size() >= 2) {
      addLevel(ZERO_PATH, "ZERO GAMMA", convertPrice(data.zeroGamma), C_ZG, false);
      addLevel(CALL_PATH, "CALL WALL", convertPrice(data.mposVol), C_CALL, false);
      addLevel(PUT_PATH, "PUT WALL", convertPrice(data.mnegVol), C_PUT, false);
      addLevel(CALL_OI_PATH, "CALL OI", convertPrice(data.mposOi), C_CALL_OI, true);
      addLevel(PUT_OI_PATH, "PUT OI", convertPrice(data.mnegOi), C_PUT_OI, true);
      addIvLevels(data);
      addDualLevels();
      addMaxChangeLevels();
      addHud(data);
    }

    if (getSettings().getBoolean(SHOW_ERRORS, true)
        && lastStatus != null
        && (lastStatus.startsWith("ERR") || lastStatus.startsWith("WARN"))) {
      String pos = trim(getSettings().getString(HUD_POS));
      if (Util.isEmpty(pos)) pos = "TR";
      PanelFigure err = new PanelFigure(lastStatus, C_REGIME_NEG, Color.WHITE, false);
      // Offset below/above the HUD stack (max ~6 rows) so it never overlaps
      err.setPlacement(8 + 6 * 28, pos.endsWith("R"), pos.startsWith("B"));
      addFigure(err);
    }

    endFigureUpdate();
    notifyRedraw();
  }

  private void addHud(MajorsData data)
  {
    String pos = trim(getSettings().getString(HUD_POS));
    if (Util.isEmpty(pos)) pos = "TR";
    boolean right = pos.endsWith("R");
    boolean bottom = pos.startsWith("B");

    // Core HUD: regime + live action plan + optional diverge warning.
    int q = quadrant(data);
    java.util.List<PanelFigure> rows = new java.util.ArrayList<>();

    if (getSettings().getBoolean(SHOW_REGIME, true)) {
      String badge;
      Color bg;
      switch (q) {
        case 1 -> { badge = "+GEX  Above Flip  (range)"; bg = C_REGIME_POS; }
        case 2 -> { badge = "-GEX  Above Flip  (moving)"; bg = C_REGIME_NEG; }
        case 3 -> { badge = "+GEX  Below Flip  (interaction)"; bg = C_REGIME_POS; }
        default -> { badge = "-GEX  Below Flip  (trend)"; bg = C_REGIME_NEG; }
      }
      rows.add(new PanelFigure(badge, bg, Color.WHITE, true));
    }

    if (getSettings().getBoolean(SHOW_STRATEGY, true)) {
      String action = buildActionPlan(data, q);
      if (!Util.isEmpty(action)) {
        rows.add(new PanelFigure(action, C_HUD_BG, new Color(235, 235, 235), false));
      }
    }

    if (getSettings().getBoolean(SHOW_HUD, false)) {
      Double zg = convertPrice(data.zeroGamma);
      Double call = convertPrice(data.mposVol);
      Double put = convertPrice(data.mnegVol);
      Double spotFx = convertPrice(data.spot);
      String src = lastTicker + (Util.isEmpty(lastFuture) ? "" : ("->" + lastFuture));

      String hud = src
          + "   Spot " + formatPrice(spotFx)
          + "   |  Call " + formatPrice(call)
          + "   Flip " + formatPrice(zg) + distToZg(spotFx, zg)
          + "   Put " + formatPrice(put)
          + "   |  Net " + formatSigned(data.netGexVol);
      rows.add(new PanelFigure(hud, C_HUD_BG, new Color(235, 235, 235), false));
    }

    if (getSettings().getBoolean(SHOW_OI_DIVERGE, true)) {
      String diverge = oiDivergeLine(data);
      if (!Util.isEmpty(diverge)) {
        rows.add(new PanelFigure(diverge, C_WARN, Color.WHITE, true));
      }
    }

    if (getSettings().getBoolean(SHOW_MAX_CHANGE, false) && lastMaxChange != null) {
      String mx = buildMaxChangeOneLine(lastMaxChange);
      if (!Util.isEmpty(mx)) {
        rows.add(new PanelFigure(mx, new Color(25, 25, 35), new Color(210, 210, 220), false));
      }
    }

    placeRows(rows, right, bottom);
  }

  /**
   * One-line playbook from regime + distance to Call / Flip / Put + OI diverge.
   * Not a signal — a bias reminder for discretionary trading.
   */
  private String buildActionPlan(MajorsData data, int q)
  {
    if (data == null || data.spot == null) return "ACTION: Waiting for spot...";

    Double spot = data.spot;
    Double call = data.mposVol;
    Double put = data.mnegVol;
    Double flip = data.zeroGamma;

    boolean nearCall = nearLevel(spot, call, put, call);
    boolean nearPut = nearLevel(spot, call, put, put);
    boolean nearFlip = nearLevel(spot, call, put, flip);
    boolean callDiv = diverges(data.mposVol, data.mposOi);
    boolean putDiv = diverges(data.mnegVol, data.mnegOi);
    boolean diverge = callDiv || putDiv;

    String base;
    if (nearCall && !nearPut) {
      base = switch (q) {
        case 1 -> "ACTION: Near Call Wall — fade/sell strength. Target Flip. Do not chase longs.";
        case 2 -> "ACTION: Near Call in -GEX — prefer short on rejection. Size down; Flip may fail.";
        case 3 -> "ACTION: Near Call below Flip — treat as resistance. Fade only with clear rejection.";
        default -> "ACTION: Near Call in -GEX trend — sell rallies. Trail if momentum holds.";
      };
    }
    else if (nearPut && !nearCall) {
      base = switch (q) {
        case 1 -> "ACTION: Near Put Wall — buy dips. Target mid-range / Flip. Stop under Put.";
        case 2 -> "ACTION: Near Put in -GEX — bounce possible but fragile. Small size or wait.";
        case 3 -> "ACTION: Near Put below Flip — look for bounce into Flip. Confirm with price.";
        default -> "ACTION: Near Put in -GEX trend — counter-trend only. Prefer wait for Flip reclaim.";
      };
    }
    else if (nearFlip) {
      base = switch (q) {
        case 1, 2 -> "ACTION: At Flip from above — watch for hold (support) or break (regime shift).";
        default -> "ACTION: At Flip from below — reclaim = long bias; fail = stay short / wait.";
      };
    }
    else {
      base = switch (q) {
        case 1 -> "ACTION: Mid-range +GEX — wait for extremes. Sell Call / buy Put; avoid mid entries.";
        case 2 -> "ACTION: Mid-range -GEX — reduce size. Wait for direction vs Flip or Call/Put.";
        case 3 -> "ACTION: Below Flip +GEX — trade Flip as magnet. Expect chop; react to tests.";
        default -> "ACTION: Below Flip -GEX — momentum short bias. Sell strength toward Flip.";
      };
    }

    if (diverge) {
      if (callDiv && putDiv) base += "  Walls Vol!=OI — confirm levels with price.";
      else if (callDiv) base += "  Call Vol!=OI — Call Wall less reliable.";
      else base += "  Put Vol!=OI — Put Wall less reliable.";
    }
    return base;
  }

  /** True if spot is near {@code level} relative to Call–Put span (or spot %). */
  private static boolean nearLevel(Double spot, Double call, Double put, Double level)
  {
    if (spot == null || level == null) return false;
    double dist = Math.abs(spot - level);
    double span = 0;
    if (call != null && put != null) span = Math.abs(call - put);
    double thresh;
    if (span > 1) thresh = Math.max(span * NEAR_FRAC, Math.abs(spot) * NEAR_SPOT_FRAC * 0.25);
    else thresh = Math.abs(spot) * NEAR_SPOT_FRAC;
    return dist <= thresh;
  }

  private void addDualLevels()
  {
    if (!getSettings().getBoolean(SHOW_DUAL_HORIZON, false)) return;
    MajorsData dual = lastDualData;
    if (dual == null) return;
    String tag = shortCat(lastDualCategory);
    addLevel(DUAL_ZERO_PATH, "ZG " + tag, convertPrice(dual.zeroGamma), C_DUAL_ZG, true);
    addLevel(DUAL_CALL_PATH, "CALL " + tag, convertPrice(dual.mposVol), C_DUAL_CALL, true);
    addLevel(DUAL_PUT_PATH, "PUT " + tag, convertPrice(dual.mnegVol), C_DUAL_PUT, true);
  }

  private void addMaxChangeLevels()
  {
    if (!getSettings().getBoolean(SHOW_MAX_CHANGE_LEVELS, false)) return;
    MaxChangeData mx = lastMaxChange;
    if (mx == null) return;
    addMxLevel("now", mx.current);
    addMxLevel("1m", mx.one);
    addMxLevel("5m", mx.five);
    addMxLevel("10m", mx.ten);
    addMxLevel("15m", mx.fifteen);
    addMxLevel("30m", mx.thirty);
  }

  private void addMxLevel(String tf, double[] pair)
  {
    if (pair == null || pair.length < 1) return;
    addLevel(MX_PATH, "dGEX " + tf, convertPrice(pair[0]), C_MX, true);
  }

  private static String shortCat(String category)
  {
    if (Util.isEmpty(category)) return "";
    String c = category.toLowerCase();
    if (c.contains("zero")) return "0D";
    if (c.contains("one")) return "1D";
    if (c.contains("full")) return "90D";
    return category;
  }

  /** Opposite structural period for overlay: 0DTE↔90D, 1DTE→90D. */
  private static String dualCategoryFor(String category)
  {
    if (Util.isEmpty(category)) return "gex_full";
    String c = category.toLowerCase();
    if (c.contains("zero")) return "gex_full";
    if (c.contains("full")) return "gex_zero";
    if (c.contains("one")) return "gex_full";
    return "gex_full";
  }

  private String oiDivergeLine(MajorsData data)
  {
    if (data == null) return null;
    boolean callDiv = diverges(data.mposVol, data.mposOi);
    boolean putDiv = diverges(data.mnegVol, data.mnegOi);
    if (!callDiv && !putDiv) return null;
    StringBuilder sb = new StringBuilder("DIVERGE Vol!=OI");
    if (callDiv) {
      sb.append("  Call ").append(formatPrice(convertPrice(data.mposVol)))
          .append(" vs ").append(formatPrice(convertPrice(data.mposOi)));
    }
    if (putDiv) {
      sb.append("  Put ").append(formatPrice(convertPrice(data.mnegVol)))
          .append(" vs ").append(formatPrice(convertPrice(data.mnegOi)));
    }
    return sb.toString();
  }

  private static boolean diverges(Double vol, Double oi)
  {
    if (vol == null || oi == null) return false;
    return Math.abs(vol - oi) >= OI_DIVERGE_PTS;
  }

  /** Stack HUD panels in the chosen chart corner (badge always on top). */
  private void placeRows(java.util.List<PanelFigure> rows, boolean right, boolean bottom)
  {
    int rowH = 28;
    int n = rows.size();
    for (int i = 0; i < n; i++) {
      PanelFigure p = rows.get(i);
      int off = bottom ? 8 + (n - 1 - i) * rowH : 8 + i * rowH;
      p.setPlacement(off, right, bottom);
      addFigure(p);
    }
  }

  /**
   * 4 Quadrants: flip = spot vs zero gamma, GEX sign = net gex (volume).
   * Q1 Above+Pos, Q2 Above+Neg, Q3 Below+Pos, Q4 Below+Neg.
   */
  private static int quadrant(MajorsData data)
  {
    boolean aboveFlip = data.spot == null || data.zeroGamma == null || data.spot >= data.zeroGamma;
    boolean posGex = data.netGexVol == null || data.netGexVol >= 0;
    if (aboveFlip) return posGex ? 1 : 2;
    return posGex ? 3 : 4;
  }

  private String buildMaxChangeOneLine(MaxChangeData mx)
  {
    StringBuilder sb = new StringBuilder("MAX dGEX");
    appendMxInline(sb, "now", mx.current);
    appendMxInline(sb, "1m", mx.one);
    appendMxInline(sb, "5m", mx.five);
    appendMxInline(sb, "10m", mx.ten);
    appendMxInline(sb, "15m", mx.fifteen);
    appendMxInline(sb, "30m", mx.thirty);
    return sb.toString();
  }

  private void appendMxInline(StringBuilder sb, String tf, double[] pair)
  {
    if (pair == null || pair.length < 2) return;
    sb.append(" | ").append(tf).append(" ")
        .append(formatPrice(convertPrice(pair[0])))
        .append(" ")
        .append(formatSigned(pair[1]));
  }

  private static String distToZg(Double spot, Double zg)
  {
    if (spot == null || zg == null) return "";
    double d = spot - zg;
    return " (" + (d >= 0 ? "+" : "") + String.format("%.0f", d) + ")";
  }

  private String ivHudSuffix(MajorsData data)
  {
    if (lastSigma1 == null || data == null || data.spot == null) return "";
    Double one = convertMove(lastSigma1);
    Double eighty = convertMove(lastSigma1 * Z_80);
    String ivPct = lastIv == null ? "" : String.format("  IV %.1f%%", lastIv * 100.0);
    return "   |  IV68 ±" + formatPrice(one) + "  IV80 ±" + formatPrice(eighty) + ivPct;
  }

  private Double convertMove(double sourcePoints)
  {
    Double a = convertPrice(0.0);
    Double b = convertPrice(sourcePoints);
    if (a == null || b == null) return sourcePoints;
    return Math.abs(b - a);
  }

  private boolean ivRangeEnabled()
  {
    PathInfo a = getSettings().getPath(IV68_PATH);
    PathInfo b = getSettings().getPath(IV80_PATH);
    boolean aOn = a == null || a.isEnabled();
    boolean bOn = b == null || b.isEnabled();
    return aOn || bOn;
  }

  private void addIvLevels(MajorsData data)
  {
    if (data == null || data.spot == null || lastSigma1 == null) return;
    addIvBand(IV68_PATH, "IV 68%", data.spot, lastSigma1, 1.0, C_IV68);
    addIvBand(IV80_PATH, "IV 80%", data.spot, lastSigma1, Z_80, C_IV80);
  }

  private void addIvBand(String pathKey, String name, double spot, double sigma1, double z, Color color)
  {
    PathInfo path = getSettings().getPath(pathKey);
    if (path != null && !path.isEnabled()) return;
    addLevel(pathKey, name + " H", convertPrice(spot + z * sigma1), color, true);
    addLevel(pathKey, name + " L", convertPrice(spot - z * sigma1), color, true);
  }

  private static Double computeSigma1(MajorsData data, Double iv, String category)
  {
    if (data == null || data.spot == null || iv == null || iv <= 0) return null;
    double dte = remainingDteDays(category);
    return data.spot * iv * Math.sqrt(dte / 365.0);
  }

  /** gexbot 0DTE formula: seconds remaining to 16:00 ET / 86400. */
  private static double remainingDteDays(String category)
  {
    ZoneId ny = ZoneId.of("America/New_York");
    ZonedDateTime now = ZonedDateTime.now(ny);
    LocalDate day = now.toLocalDate();
    if (!now.toLocalTime().isBefore(LocalTime.of(16, 0))) day = day.plusDays(1);
    while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
      day = day.plusDays(1);
    }
    ZonedDateTime close = day.atTime(16, 0).atZone(ny);
    double frac = Math.max(60.0, Duration.between(now, close).getSeconds()) / 86400.0;
    if ("gex_one".equalsIgnoreCase(category) || "one".equalsIgnoreCase(category)) frac += 1.0;
    return frac;
  }

  private Double fetchAtmIv(String ticker)
  {
    double manual = getSettings().getDouble(ATM_IV, 0.0);
    if (manual > 0.5) return manual / 100.0;
    String yahoo = ivYahooSymbol(ticker);
    Double px = fetchPublicLast(yahoo);
    if (px != null && px > 1 && px < 250) {
      logDebug("ATM IV from " + yahoo + " = " + px);
      return px / 100.0;
    }
    logDebug("ATM IV unavailable for " + ticker + " (" + yahoo + ")");
    return lastIv;
  }

  private static String ivYahooSymbol(String ticker)
  {
    String t = ticker == null ? "" : ticker.toUpperCase();
    if (t.contains("NDX") || t.contains("QQQ") || t.equals("NQ")) return "%5EVXN";
    if (t.contains("RUT") || t.contains("IWM") || t.equals("RTY")) return "%5ERVX";
    return "%5EVIX";
  }

  private Double fetchPublicLast(String yahooSymbol)
  {
    try {
      String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol + "?interval=1d&range=1d";
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(8000);
      conn.setReadTimeout(8000);
      conn.setRequestProperty("User-Agent", "MotiveWave-GexbotMajors/1.4");
      conn.setRequestProperty("Accept", "application/json");
      int code = conn.getResponseCode();
      String body = readBody(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
      if (code < 200 || code >= 300) {
        logDebug("IV HTTP " + code + " " + abbreviate(body, 120));
        return null;
      }
      Double px = MajorsData.readNumber(body, "regularMarketPrice");
      if (px == null) px = MajorsData.readNumber(body, "regularMarketPreviousClose");
      return px;
    }
    catch (Exception ex) {
      logDebug("IV fetch " + safeMsg(ex.getMessage()));
      return null;
    }
  }

  private void addLevel(String pathKey, String name, Double price, Color fallbackColor, boolean dashed)
  {
    if (price == null || Double.isNaN(price)) return;
    PathInfo path = getSettings().getPath(pathKey);
    if (path != null && !path.isEnabled()) return;

    Color color = fallbackColor;
    float width = 1.0f;
    if (path != null) {
      if (path.getColor() != null) color = path.getColor();
      if (path.getStrokeWidth() > 0) width = path.getStrokeWidth();
    }

    boolean showLabel = getSettings().getBoolean(SHOW_LEVEL_LABELS, true);
    String label = showLabel ? (name + "  " + formatPrice(price)) : null;
    addFigure(new LevelFigure(price, color, width, label, dashed));
  }

  /** Solid thin level line with a plain colored text label above it. */
  private static class LevelFigure extends Figure
  {
    private final double price;
    private final Color color;
    private final float width;
    private final String label;
    private final boolean dashed;
    private Line2D.Double line;

    LevelFigure(double price, Color color, float width, String label, boolean dashed)
    {
      this.price = price;
      this.color = color;
      this.width = width;
      this.label = label;
      this.dashed = dashed;
    }

    @Override
    public boolean isVisible(DrawContext ctx)
    {
      return true;
    }

    @Override
    public void layout(DrawContext ctx)
    {
      Rectangle gb = ctx.getBounds();
      double y = ctx.translateValueD(price);
      line = new Line2D.Double(gb.getX(), y, gb.getMaxX(), y);
      setBounds(new Rectangle2D.Double(gb.getX(), y - 20, gb.getWidth(), 24));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      if (line == null) return;
      if (dashed) {
        gc.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] { 6f, 5f }, 0f));
      }
      else {
        gc.setStroke(new BasicStroke(width));
      }
      gc.setColor(color);
      gc.draw(line);

      if (label != null) {
        Font font = new Font("SansSerif", Font.BOLD, 12);
        gc.setFont(font);
        FontMetrics fm = gc.getFontMetrics(font);
        Rectangle gb = ctx.getBounds();
        int tw = fm.stringWidth(label);
        // Plain colored text just above the line, right-aligned
        int tx = (int) gb.getMaxX() - tw - 12;
        int ty = (int) Math.round(line.getY1()) - 5;
        gc.setColor(color);
        gc.drawString(label, tx, ty);
      }
    }
  }

  /** Screen-space HUD panel anchored in a chart corner (TL/TR/BL/BR). */
  private static class PanelFigure extends Figure
  {
    private final String text;
    private final Color bg;
    private final Color fg;
    private final boolean bold;
    private int yOff = 8;
    private boolean right;
    private boolean bottom;
    private Rectangle2D.Double box;

    PanelFigure(String text, Color bg, Color fg, boolean bold)
    {
      this.text = text;
      this.bg = bg;
      this.fg = fg;
      this.bold = bold;
    }

    void setPlacement(int yOff, boolean right, boolean bottom)
    {
      this.yOff = yOff;
      this.right = right;
      this.bottom = bottom;
    }

    @Override
    public boolean isVisible(DrawContext ctx)
    {
      return true;
    }

    @Override
    public void layout(DrawContext ctx)
    {
      // Hit-test area = the panel box only (estimated; refined in draw).
      Rectangle gb = ctx.getBounds();
      int boxW = (text == null ? 0 : text.length() * 7) + 20;
      int boxH = 24;
      int bx = right ? (int) gb.getMaxX() - boxW - 8 : (int) gb.getX() + 8;
      int by = bottom ? (int) gb.getMaxY() - yOff - boxH : (int) gb.getY() + yOff;
      setBounds(new Rectangle2D.Double(bx, by, boxW, boxH));
    }

    @Override
    public void draw(Graphics2D gc, DrawContext ctx)
    {
      if (Util.isEmpty(text)) return;
      Rectangle gb = ctx.getBounds();
      Font font = new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, bold ? 13 : 12);
      gc.setFont(font);
      FontMetrics fm = gc.getFontMetrics(font);
      int tw = fm.stringWidth(text);
      int th = fm.getHeight();
      int padX = 10;
      int padY = 6;
      int boxW = tw + padX * 2;
      int boxH = th + padY;
      int bx = right ? (int) gb.getMaxX() - boxW - 8 : (int) gb.getX() + 8;
      int by = bottom ? (int) gb.getMaxY() - yOff - boxH : (int) gb.getY() + yOff;
      box = new Rectangle2D.Double(bx, by, boxW, boxH);
      setBounds(box);

      gc.setColor(bg);
      gc.fill(box);
      gc.setColor(fg);
      gc.draw(box);
      gc.drawString(text, bx + padX, by + fm.getAscent() + padY / 2);
    }
  }

  private ResolvedRequest resolveRequest(DataContext ctx)
  {
    String apiKey = trim(getSettings().getString(API_KEY));
    String tickerSel = trim(getSettings().getString(TICKER));
    String futuresMode = trim(getSettings().getString(FUTURES_TARGET));
    String category = trim(getSettings().getString(CATEGORY));

    if (Util.isEmpty(category)) category = "gex_zero";
    if (Util.isEmpty(futuresMode)) futuresMode = "AUTO";
    if (Util.isEmpty(tickerSel)) tickerSel = "AUTO";

    String chartRoot = chartRoot(ctx);
    String ticker;
    if ("AUTO".equalsIgnoreCase(tickerSel)) {
      if ("NQ".equals(chartRoot) || "MNQ".equals(chartRoot)) ticker = "QQQ";
      else if ("ES".equals(chartRoot) || "MES".equals(chartRoot)) ticker = "SPY";
      else if ("RTY".equals(chartRoot) || "M2K".equals(chartRoot)) ticker = "IWM";
      else ticker = "QQQ";
    }
    else {
      ticker = sanitizeTicker(tickerSel.toUpperCase());
    }

    String futuresTarget;
    if ("NONE".equalsIgnoreCase(futuresMode)) futuresTarget = null;
    else if ("AUTO".equalsIgnoreCase(futuresMode)) futuresTarget = autoFutureFor(ticker, chartRoot);
    else futuresTarget = futuresMode.toUpperCase();

    if ("NQ_NDX".equals(ticker) || "ES_SPX".equals(ticker)) futuresTarget = null;
    return new ResolvedRequest(apiKey, ticker, futuresTarget, category);
  }

  private static String sanitizeTicker(String ticker)
  {
    if ("NQ".equals(ticker) || "MNQ".equals(ticker)) return "QQQ";
    if ("ES".equals(ticker) || "MES".equals(ticker)) return "SPY";
    if ("RTY".equals(ticker) || "M2K".equals(ticker)) return "IWM";
    if (ticker.length() < 2) return "QQQ";
    return ticker;
  }

  private static boolean needsConversion(String ticker)
  {
    return !("NQ_NDX".equals(ticker) || "ES_SPX".equals(ticker));
  }

  private static String chartRoot(DataContext ctx)
  {
    try {
      if (ctx == null || ctx.getInstrument() == null) return "";
      String sym = trim(ctx.getInstrument().getSymbol()).toUpperCase();
      if (Util.isEmpty(sym)) return "";
      if (sym.startsWith("MNQ")) return "MNQ";
      if (sym.startsWith("MES")) return "MES";
      if (sym.startsWith("M2K")) return "M2K";
      if (sym.startsWith("NQ")) return "NQ";
      if (sym.startsWith("ES")) return "ES";
      if (sym.startsWith("RTY")) return "RTY";
      return sym.replaceAll("[0-9].*", "");
    }
    catch (Exception ex) {
      return "";
    }
  }

  private static String autoFutureFor(String ticker, String chartRoot)
  {
    if ("QQQ".equals(ticker) || "NDX".equals(ticker)) return "NQ";
    if ("SPY".equals(ticker) || "SPX".equals(ticker)) return "ES";
    if ("IWM".equals(ticker) || "RUT".equals(ticker)) return "RTY";
    if ("NQ".equals(chartRoot) || "MNQ".equals(chartRoot)) return "NQ";
    if ("ES".equals(chartRoot) || "MES".equals(chartRoot)) return "ES";
    if ("RTY".equals(chartRoot) || "M2K".equals(chartRoot)) return "RTY";
    return null;
  }

  private Double convertPrice(double raw)
  {
    return convertPrice(Double.valueOf(raw));
  }

  private Double convertPrice(Double raw)
  {
    if (raw == null || Double.isNaN(raw)) return null;
    double mult = getSettings().getDouble(STRIKE_MULT, 1.0);
    double add = getSettings().getDouble(STRIKE_ADD, 0.0);
    Conversion c = lastConversion != null ? lastConversion : Conversion.identity();
    return raw * c.multiplier * mult + c.additive + add;
  }

  private MajorsData fetchMajors(ResolvedRequest req) throws Exception
  {
    if (Util.isEmpty(req.apiKey)) {
      setStatus("ERR paste your Custom API key (gexbot_custom_...)");
      return null;
    }
    if (req.apiKey.startsWith("gexbot_motivewave_")) {
      setStatus("ERR use Custom key, not MotiveWave key");
      return null;
    }

    String url = BASE_URL + "/" + req.ticker + "/classic/" + req.category + "/majors";
    logDebug("GET " + url);
    HttpURLConnection conn = openGet(url, req.apiKey);
    int code = conn.getResponseCode();
    String body = readBody(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
    logDebug("HTTP " + code + " " + abbreviate(body, 220));
    if (code < 200 || code >= 300) {
      setStatus("ERR HTTP " + code + " " + abbreviate(body, 90));
      return null;
    }
    MajorsData data = MajorsData.parse(body);
    if (data == null) setStatus("ERR JSON majors");
    return data;
  }

  private MaxChangeData fetchMaxChange(ResolvedRequest req) throws Exception
  {
    String url = BASE_URL + "/" + req.ticker + "/classic/" + req.category + "/maxchange";
    logDebug("GET " + url);
    HttpURLConnection conn = openGet(url, req.apiKey);
    int code = conn.getResponseCode();
    String body = readBody(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
    logDebug("maxchange HTTP " + code + " " + abbreviate(body, 220));
    if (code < 200 || code >= 300) return null;
    return MaxChangeData.parse(body);
  }

  private Conversion fetchConversion(String apiKey, String ticker, String future) throws Exception
  {
    String source = ticker;
    if ("NQ_NDX".equalsIgnoreCase(ticker)) source = "NDX";
    if ("ES_SPX".equalsIgnoreCase(ticker)) source = "SPX";

    String url = BASE_URL + "/futures/conversion?ticker=" + source + "&future=" + future;
    HttpURLConnection conn = openGet(url, apiKey);
    int code = conn.getResponseCode();
    String body = readBody(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
    logDebug("conversion HTTP " + code + " " + abbreviate(body, 180));
    if (code < 200 || code >= 300) return Conversion.identity();

    Double mult = MajorsData.readNumber(body, "multiplier");
    Double add = MajorsData.readNumber(body, "additive");
    return new Conversion(mult == null ? 1.0 : mult, add == null ? 0.0 : add);
  }

  private static HttpURLConnection openGet(String url, String apiKey) throws Exception
  {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(15000);
    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
    conn.setRequestProperty("User-Agent", "MotiveWave-GexbotMajors/1.4");
    conn.setRequestProperty("Accept", "application/json");
    return conn;
  }

  private void setStatus(String status)
  {
    lastStatus = status;
    logDebug(status);
    debug("GexbotMajors: " + status);
  }

  private static void logDebug(String msg)
  {
    try {
      Files.createDirectories(DEBUG_LOG.getParent());
      try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
          Files.newOutputStream(DEBUG_LOG, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND),
          StandardCharsets.UTF_8))) {
        out.println(new Date() + " | " + msg);
      }
    }
    catch (Exception ignored) {
    }
  }

  private static String readBody(java.io.InputStream in) throws Exception
  {
    if (in == null) return "";
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    return sb.toString();
  }

  private static String trim(String s) { return s == null ? "" : s.trim(); }
  private static String safeMsg(String s) { return s == null ? "unknown" : abbreviate(s, 120); }

  private static String abbreviate(String s, int max)
  {
    if (s == null) return "";
    s = s.replace('\n', ' ').replace('\r', ' ');
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }

  private static String formatPrice(Double price)
  {
    if (price == null || Double.isNaN(price)) return "-";
    if (Math.abs(price - Math.rint(price)) < 0.05) return String.format("%.0f", price);
    return String.format("%.1f", price);
  }

  private static String formatSigned(Double v)
  {
    if (v == null || Double.isNaN(v)) return "-";
    String s = String.format("%.0f", Math.abs(v));
    if (Math.abs(v) >= 1000) s = String.format("%.1fk", v / 1000.0);
    else s = String.format("%.0f", v);
    if (v > 0 && !s.startsWith("+")) return "+" + s;
    return s;
  }

  private static String formatSigned(double v)
  {
    return formatSigned(Double.valueOf(v));
  }

  static class ResolvedRequest
  {
    final String apiKey, ticker, futuresTarget, category;
    ResolvedRequest(String apiKey, String ticker, String futuresTarget, String category)
    {
      this.apiKey = apiKey;
      this.ticker = ticker;
      this.futuresTarget = futuresTarget;
      this.category = category;
    }

    ResolvedRequest withCategory(String cat)
    {
      return new ResolvedRequest(apiKey, ticker, futuresTarget, cat);
    }
  }

  static class Conversion
  {
    final double multiplier, additive;
    Conversion(double multiplier, double additive)
    {
      this.multiplier = multiplier;
      this.additive = additive;
    }
    static Conversion identity() { return new Conversion(1.0, 0.0); }
  }

  static class MajorsData
  {
    Double zeroGamma, mposVol, mnegVol, mposOi, mnegOi, spot, netGexVol, netGexOi;

    static MajorsData parse(String json)
    {
      if (Util.isEmpty(json)) return null;
      MajorsData d = new MajorsData();
      d.zeroGamma = readNumber(json, "zero_gamma");
      d.mposVol = readNumber(json, "mpos_vol");
      d.mnegVol = readNumber(json, "mneg_vol");
      d.mposOi = readNumber(json, "mpos_oi");
      d.mnegOi = readNumber(json, "mneg_oi");
      d.spot = readNumber(json, "spot");
      d.netGexVol = readNumber(json, "net_gex_vol");
      d.netGexOi = readNumber(json, "net_gex_oi");
      if (d.zeroGamma == null && d.mposVol == null && d.mnegVol == null
          && d.mposOi == null && d.mnegOi == null) return null;
      return d;
    }

    static Double readNumber(String json, String key)
    {
      String needle = "\"" + key + "\"";
      int i = json.indexOf(needle);
      if (i < 0) return null;
      int colon = json.indexOf(':', i + needle.length());
      if (colon < 0) return null;
      int start = colon + 1;
      while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
      if (start < json.length() && json.regionMatches(true, start, "null", 0, 4)) return null;
      int end = start;
      while (end < json.length()) {
        char c = json.charAt(end);
        if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') end++;
        else break;
      }
      if (end <= start) return null;
      try { return Double.parseDouble(json.substring(start, end)); }
      catch (NumberFormatException ex) { return null; }
    }
  }

  static class MaxChangeData
  {
    double[] current, one, five, ten, fifteen, thirty;

    static MaxChangeData parse(String json)
    {
      if (Util.isEmpty(json)) return null;
      MaxChangeData d = new MaxChangeData();
      d.current = readPair(json, "current");
      if (d.current == null) d.current = readPair(json, "now");
      d.one = readPair(json, "one");
      d.five = readPair(json, "five");
      d.ten = readPair(json, "ten");
      d.fifteen = readPair(json, "fifteen");
      d.thirty = readPair(json, "thirty");
      if (d.current == null && d.one == null && d.five == null
          && d.ten == null && d.fifteen == null && d.thirty == null) return null;
      return d;
    }

    static double[] readPair(String json, String key)
    {
      String needle = "\"" + key + "\"";
      int i = json.indexOf(needle);
      if (i < 0) return null;
      int lb = json.indexOf('[', i + needle.length());
      int rb = json.indexOf(']', lb);
      if (lb < 0 || rb < 0) return null;
      String inside = json.substring(lb + 1, rb).trim();
      String[] parts = inside.split(",");
      if (parts.length < 2) return null;
      try {
        return new double[] {
            Double.parseDouble(parts[0].trim()),
            Double.parseDouble(parts[1].trim())
        };
      }
      catch (Exception ex) {
        return null;
      }
    }
  }
}
