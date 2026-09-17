package gexbot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import com.motivewave.platform.sdk.common.Coordinate;
import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.Enums;
import com.motivewave.platform.sdk.common.NVP;
import com.motivewave.platform.sdk.common.PathInfo;
import com.motivewave.platform.sdk.common.Util;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.DiscreteDescriptor;
import com.motivewave.platform.sdk.common.desc.DoubleDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.PathDescriptor;
import com.motivewave.platform.sdk.common.desc.StringDescriptor;
import com.motivewave.platform.sdk.draw.Label;
import com.motivewave.platform.sdk.draw.Line;
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
    desc = "HUD Regime + Call/Put Wall + Zero Gamma (Classic). Requires a Custom API key.",
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

  static final String SHOW_HUD = "showHud";
  static final String SHOW_REGIME = "showRegime";
  static final String SHOW_LEVEL_LABELS = "showLevelLabels";
  static final String SHOW_MAX_CHANGE = "showMaxChange";
  static final String SHOW_ERRORS = "showErrors";

  static final String BASE_URL = "https://api.gex.bot/v2";
  static final Path DEBUG_LOG = Path.of(System.getProperty("user.home"), "MotiveWave Extensions", "gexbot_majors_debug.txt");

  static final Color C_CALL = new Color(46, 204, 113);
  static final Color C_PUT = new Color(231, 76, 60);
  static final Color C_ZG = new Color(243, 156, 18);
  static final Color C_HUD_BG = new Color(20, 22, 28);
  static final Color C_REGIME_NEG = new Color(170, 35, 35);
  static final Color C_REGIME_POS = new Color(25, 120, 65);

  private final AtomicBoolean fetching = new AtomicBoolean(false);
  private volatile MajorsData lastData;
  private volatile MaxChangeData lastMaxChange;
  private volatile Conversion lastConversion = Conversion.identity();
  private volatile String lastTicker = "";
  private volatile String lastFuture = "";
  private volatile long lastFetchMs;
  private volatile String lastStatus = "Waiting...";
  private volatile DataContext latestCtx;

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

    tab = sd.addTab("Display");
    grp = tab.addGroup("Levels");
    grp.addRow(new PathDescriptor(ZERO_PATH, "Zero Gamma", C_ZG, 2.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(CALL_PATH, "Call Wall", C_CALL, 2.0f, null, true, false, true));
    grp.addRow(new PathDescriptor(PUT_PATH, "Put Wall", C_PUT, 2.0f, null, true, false, true));

    grp = tab.addGroup("UX / HUD (keep chart clean)");
    grp.addRow(new BooleanDescriptor(SHOW_REGIME, "Regime Badge", true));
    grp.addRow(new BooleanDescriptor(SHOW_HUD, "Compact Info Panel", true));
    grp.addRow(new BooleanDescriptor(SHOW_LEVEL_LABELS, "Level Labels (right)", true));
    grp.addRow(new BooleanDescriptor(SHOW_MAX_CHANGE, "Max Change Panel", true));
    grp.addRow(new BooleanDescriptor(SHOW_ERRORS, "Show Errors Only", true));

    var rd = createRD();
    rd.setLabelSettings(TICKER, FUTURES_TARGET, CATEGORY);
  }

  @Override
  public void onLoad(Defaults defaults)
  {
    lastFetchMs = 0;
    lastStatus = "Waiting...";
    lastData = null;
    lastMaxChange = null;
    lastConversion = Conversion.identity();
    logDebug("onLoad");
  }

  @Override
  public void destroy()
  {
    lastData = null;
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
        MaxChangeData mx = null;
        if (getSettings().getBoolean(SHOW_MAX_CHANGE, true)) {
          mx = fetchMaxChange(req);
        }

        if (data != null) {
          lastData = data;
          lastMaxChange = mx;
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
      long t1 = series.getStartTime(0);
      long t2 = series.getStartTime(series.size() - 1);

      // Text is drawn ON the line (MotiveWave Label figures are unreliable here)
      addLevel(ZERO_PATH, "ZERO GAMMA", convertPrice(data.zeroGamma), t1, t2, C_ZG);
      addLevel(CALL_PATH, "CALL WALL", convertPrice(data.mposVol), t1, t2, C_CALL);
      addLevel(PUT_PATH, "PUT WALL", convertPrice(data.mnegVol), t1, t2, C_PUT);

      addHud(ctx, data);
    }

    if (getSettings().getBoolean(SHOW_ERRORS, true)
        && lastStatus != null
        && (lastStatus.startsWith("ERR") || lastStatus.startsWith("WARN"))) {
      addErrorLabel(ctx);
    }

    endFigureUpdate();
    notifyRedraw();
  }

  private void addHud(DataContext ctx, MajorsData data)
  {
    var series = ctx.getDataSeries();
    if (series == null || series.size() < 2) return;

    int idx = series.size() - 1;
    long t = series.getStartTime(idx);
    double px = series.getHigh(idx);
    if (Double.isNaN(px)) px = series.getClose(idx);

    boolean negative = isNegativeRegime(data);
    String regimeTxt = negative ? "NEGATIVE REGIME" : "POSITIVE REGIME";
    Color regimeBg = negative ? C_REGIME_NEG : C_REGIME_POS;

    if (getSettings().getBoolean(SHOW_REGIME, true)) {
      // Use a short guide line + text so the badge always renders with the study lines
      Line regimeLine = new Line(t, px, t, px);
      regimeLine.setColor(regimeBg);
      regimeLine.setStroke(new BasicStroke(1f));
      regimeLine.setText(regimeTxt, new Font("SansSerif", Font.BOLD, 14));
      if (regimeLine.getText() != null) {
        regimeLine.getText().setTextColor(Color.WHITE);
        regimeLine.getText().setBackground(regimeBg);
        regimeLine.getText().setShowOutline(true);
        regimeLine.getText().setShowBorder(true);
        regimeLine.getText().setBorderColor(Color.WHITE);
        regimeLine.getText().setInsets(5, 10, 5, 10);
      }
      addFigure(regimeLine);
    }

    if (getSettings().getBoolean(SHOW_HUD, true)) {
      Double zg = convertPrice(data.zeroGamma);
      Double call = convertPrice(data.mposVol);
      Double put = convertPrice(data.mnegVol);
      Double spotFx = convertPrice(data.spot);
      String src = lastTicker + (Util.isEmpty(lastFuture) ? "" : ("->" + lastFuture));

      // One compact single-line HUD (no multiline - MW often drops \n labels)
      String hud = src + " | Spot " + formatPrice(spotFx)
          + " | ZG " + formatPrice(zg) + distToZg(spotFx, zg)
          + " | Net " + formatSigned(data.netGexVol)
          + " | Call " + formatPrice(call)
          + " | Put " + formatPrice(put);

      // Place slightly below the regime badge using last low as anchor
      double hudY = series.getLow(idx);
      if (Double.isNaN(hudY)) hudY = px;
      Line hudLine = new Line(t, hudY, t, hudY);
      hudLine.setColor(C_HUD_BG);
      hudLine.setStroke(new BasicStroke(1f));
      hudLine.setText(hud, new Font("SansSerif", Font.PLAIN, 12));
      if (hudLine.getText() != null) {
        hudLine.getText().setTextColor(new Color(235, 235, 235));
        hudLine.getText().setBackground(C_HUD_BG);
        hudLine.getText().setShowOutline(true);
        hudLine.getText().setShowBorder(true);
        hudLine.getText().setBorderColor(new Color(90, 90, 90));
        hudLine.getText().setInsets(4, 8, 4, 8);
      }
      addFigure(hudLine);
    }

    if (getSettings().getBoolean(SHOW_MAX_CHANGE, true) && lastMaxChange != null) {
      String mx = buildMaxChangeOneLine(lastMaxChange);
      if (!Util.isEmpty(mx)) {
        double mid = (series.getHigh(idx) + series.getLow(idx)) / 2.0;
        if (Double.isNaN(mid)) mid = px;
        Line mxLine = new Line(t, mid, t, mid);
        mxLine.setColor(new Color(40, 40, 55));
        mxLine.setStroke(new BasicStroke(1f));
        mxLine.setText(mx, new Font("SansSerif", Font.PLAIN, 11));
        if (mxLine.getText() != null) {
          mxLine.getText().setTextColor(new Color(210, 210, 220));
          mxLine.getText().setBackground(new Color(25, 25, 35));
          mxLine.getText().setShowOutline(true);
          mxLine.getText().setShowBorder(true);
          mxLine.getText().setBorderColor(new Color(80, 80, 100));
          mxLine.getText().setInsets(3, 6, 3, 6);
        }
        addFigure(mxLine);
      }
    }
  }

  private String buildMaxChangeOneLine(MaxChangeData mx)
  {
    StringBuilder sb = new StringBuilder("MAX dGEX");
    appendMxInline(sb, "1m", mx.one);
    appendMxInline(sb, "5m", mx.five);
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

  private String buildMaxChangeText(MaxChangeData mx)
  {
    return buildMaxChangeOneLine(mx);
  }

  private void appendMx(StringBuilder sb, String tf, double[] pair)
  {
    appendMxInline(sb, tf, pair);
  }

  private static boolean isNegativeRegime(MajorsData data)
  {
    if (data.spot != null && data.zeroGamma != null) {
      return data.spot < data.zeroGamma;
    }
    if (data.netGexVol != null) return data.netGexVol < 0;
    return false;
  }

  private static String distToZg(Double spot, Double zg)
  {
    if (spot == null || zg == null) return "";
    double d = spot - zg;
    return " (" + (d >= 0 ? "+" : "") + String.format("%.0f", d) + ")";
  }

  private void addErrorLabel(DataContext ctx)
  {
    var series = ctx.getDataSeries();
    if (series == null || series.size() < 1) return;
    int idx = series.size() - 1;
    long t = series.getStartTime(idx);
    double px = series.getClose(idx);
    Line err = new Line(t, px, t, px);
    err.setColor(C_PUT);
    err.setText(lastStatus, new Font("SansSerif", Font.BOLD, 12));
    if (err.getText() != null) {
      err.getText().setTextColor(Color.WHITE);
      err.getText().setBackground(C_REGIME_NEG);
      err.getText().setShowOutline(true);
      err.getText().setInsets(4, 8, 4, 8);
    }
    addFigure(err);
  }

  private void addLevel(String pathKey, String name, Double price, long t1, long t2, Color fallbackColor)
  {
    if (price == null || Double.isNaN(price)) return;
    PathInfo path = getSettings().getPath(pathKey);
    if (path != null && !path.isEnabled()) return;

    Color color = fallbackColor;
    float width = 2.0f;
    if (path != null) {
      if (path.getColor() != null) color = path.getColor();
      if (path.getStrokeWidth() > 0) width = path.getStrokeWidth();
    }

    Line line = new Line(t1, price, t2, price);
    line.setExtendLeftBounds(true);
    line.setExtendRightBounds(true);
    line.setColor(color);
    line.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
        new float[] { 8f, 5f }, 0f));

    if (getSettings().getBoolean(SHOW_LEVEL_LABELS, true)) {
      line.setText(name + "  " + formatPrice(price), new Font("SansSerif", Font.BOLD, 12));
      if (line.getText() != null) {
        line.getText().setTextColor(Color.WHITE);
        line.getText().setBackground(color);
        line.getText().setShowOutline(true);
        line.getText().setShowBorder(true);
        line.getText().setBorderColor(Color.WHITE);
        line.getText().setInsets(3, 7, 3, 7);
      }
    }
    addFigure(line);
  }

  private static void styleBadge(Label label, Color text, Color bg, Font font, Color border)
  {
    label.getText().setTextColor(text);
    label.getText().setFont(font);
    label.getText().setShowOutline(true);
    label.getText().setShowBorder(true);
    label.getText().setBorderColor(border);
    label.getText().setBackground(bg);
    label.getText().setInsets(4, 8, 4, 8);
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
    conn.setRequestProperty("User-Agent", "MotiveWave-GexbotMajors/1.3");
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
    Double zeroGamma, mposVol, mnegVol, spot, netGexVol, netGexOi;

    static MajorsData parse(String json)
    {
      if (Util.isEmpty(json)) return null;
      MajorsData d = new MajorsData();
      d.zeroGamma = readNumber(json, "zero_gamma");
      d.mposVol = readNumber(json, "mpos_vol");
      d.mnegVol = readNumber(json, "mneg_vol");
      d.spot = readNumber(json, "spot");
      d.netGexVol = readNumber(json, "net_gex_vol");
      d.netGexOi = readNumber(json, "net_gex_oi");
      if (d.zeroGamma == null && d.mposVol == null && d.mnegVol == null) return null;
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
    double[] one, five, fifteen, thirty;

    static MaxChangeData parse(String json)
    {
      if (Util.isEmpty(json)) return null;
      MaxChangeData d = new MaxChangeData();
      d.one = readPair(json, "one");
      d.five = readPair(json, "five");
      d.fifteen = readPair(json, "fifteen");
      d.thirty = readPair(json, "thirty");
      if (d.one == null && d.five == null && d.fifteen == null && d.thirty == null) return null;
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
