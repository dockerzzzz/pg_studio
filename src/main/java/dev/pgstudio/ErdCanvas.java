package dev.pgstudio;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ERD 캔버스: 테이블 박스(컬럼/타입/PK·FK 표시)와 FK 관계선(까마귀발 표기)을 그린다.
 * 박스 드래그 이동, 배경 드래그 이동, 휠 스크롤, Ctrl/Cmd+휠 확대·축소, 클릭 선택(관계 강조), 더블클릭 열기.
 */
final class ErdCanvas extends JPanel {
    enum Mode {
        ALL("전체 컬럼"), KEYS("PK/FK만"), NAME("테이블명만");
        final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** 선 모양: 식별=실선, 비식별=점선(긴 점선), 추정(FK 없음)=흐린 짧은 점선. */
    enum Style { SOLID, DASHED, DOTTED }

    static Style styleOf(ErdModel.Fk f) { return f.inferred() ? Style.DOTTED : f.identifying() ? Style.SOLID : Style.DASHED; }

    static final int HEADER_H = 30, ROW_H = 22, PAD = 12, BADGE_W = 30, MAX_ROWS = 30, STUB = 24;

    private ErdModel model = new ErdModel();
    private final Map<Long, Point> pos = new HashMap<>();
    private final Map<Long, Dimension> size = new HashMap<>();
    private final List<Long> order = new ArrayList<>();   // 그리는 순서(선택된 테이블이 맨 위)
    private Mode mode = Mode.ALL;
    private double scale = 1, ox = 20, oy = 20;
    private Long selected;
    private boolean showInferred = true;
    private boolean needsFit;
    private String emptyMessage = "";
    private final Consumer<ErdModel.Table> onOpen;

    ErdCanvas(Consumer<ErdModel.Table> onOpen) {
        this.onOpen = onOpen;
        setToolTipText("");
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { if (needsFit && getWidth() > 0 && getHeight() > 0) fit(); }
        });
        MouseAdapter ma = new MouseAdapter() {
            private Point last;
            private ErdModel.Table dragging;

            @Override public void mousePressed(MouseEvent e) {
                last = e.getPoint();
                ErdModel.Table t = tableAt(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) { select(t == null ? null : t.oid); dragging = SwingUtilities.isLeftMouseButton(e) ? t : null; }
                if (e.isPopupTrigger() && t != null) popup(e, t);
            }

            @Override public void mouseReleased(MouseEvent e) {
                dragging = null;
                if (e.isPopupTrigger()) { ErdModel.Table t = tableAt(e.getPoint()); if (t != null) popup(e, t); }
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (last == null || !SwingUtilities.isLeftMouseButton(e)) return;
                int dx = e.getX() - last.x, dy = e.getY() - last.y;
                last = e.getPoint();
                if (dragging != null) { pos.get(dragging.oid).translate((int) Math.round(dx / scale), (int) Math.round(dy / scale)); }
                else { ox += dx; oy += dy; }
                repaint();
            }

            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    ErdModel.Table t = tableAt(e.getPoint());
                    if (t != null) onOpen.accept(t);
                }
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) zoom(Math.pow(1.1, -e.getPreciseWheelRotation()), e.getPoint());
                else if (e.isShiftDown()) ox -= e.getPreciseWheelRotation() * 30;
                else oy -= e.getPreciseWheelRotation() * 30;
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    // ---- 상태 ----

    void setModel(ErdModel m, String emptyMessage) {
        this.model = m;
        this.emptyMessage = emptyMessage;
        selected = null;
        autoLayout();
    }

    void setMode(Mode m) { this.mode = m; autoLayout(); }

    void setShowInferred(boolean show) { this.showInferred = show; repaint(); }

    /** 현재 표시 중인 관계(추정 관계 숨김 옵션 반영). */
    List<ErdModel.Fk> visibleFks() {
        return showInferred ? model.fks : model.fks.stream().filter(f -> !f.inferred()).toList();
    }

    /** 크기를 다시 계산하고 자동 배치한 뒤 화면에 맞춘다. */
    void autoLayout() {
        computeSizes();
        pos.clear();
        pos.putAll(ErdLayout.compute(model, size));
        order.clear();
        order.addAll(pos.keySet());
        needsFit = true;
        if (getWidth() > 0 && getHeight() > 0) fit();
        repaint();
    }

    double scale() { return scale; }
    Point position(long oid) { return pos.get(oid); }
    Dimension sizeOf(long oid) { return size.get(oid); }
    Long selected() { return selected; }
    ErdModel model() { return model; }

    /** 테이블 박스 중앙의 화면 좌표(테스트/조작용). */
    Point screenCenter(long oid) {
        Point p = pos.get(oid);
        Dimension d = size.get(oid);
        return new Point((int) Math.round(ox + (p.x + d.width / 2.0) * scale), (int) Math.round(oy + (p.y + d.height / 2.0) * scale));
    }

    void select(Long oid) {
        selected = oid;
        if (oid != null && order.remove(oid)) order.add(oid);
        repaint();
    }

    /** 이름에 text 가 들어 있는 첫 테이블을 선택하고 화면 중앙으로 가져온다. 없으면 false. */
    boolean focusTable(String text) {
        String q = text.strip().toLowerCase();
        if (q.isEmpty()) return false;
        for (ErdModel.Table t : model.tables.values()) {
            if (t.name.toLowerCase().contains(q)) {
                select(t.oid);
                Point p = pos.get(t.oid);
                Dimension d = size.get(t.oid);
                ox = getWidth() / 2.0 - (p.x + d.width / 2.0) * scale;
                oy = getHeight() / 2.0 - (p.y + d.height / 2.0) * scale;
                repaint();
                return true;
            }
        }
        return false;
    }

    void zoom(double factor, Point anchor) {
        double ns = Math.max(0.15, Math.min(3.0, scale * factor));
        double wx = (anchor.x - ox) / scale, wy = (anchor.y - oy) / scale;
        scale = ns;
        ox = anchor.x - wx * ns;
        oy = anchor.y - wy * ns;
        repaint();
    }

    void fit() {
        needsFit = false;
        Rectangle2D b = worldBounds();
        double w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        scale = Math.max(0.15, Math.min(1.0, Math.min((w - 40) / b.getWidth(), (h - 40) / b.getHeight())));
        ox = (w - b.getWidth() * scale) / 2 - b.getX() * scale;
        oy = Math.max(20, (h - b.getHeight() * scale) / 2) - (Math.max(20, (h - b.getHeight() * scale) / 2) > 20 ? b.getY() * scale : b.getY() * scale);
        repaint();
    }

    private Rectangle2D worldBounds() {
        Rectangle2D r = null;
        for (var e : pos.entrySet()) {
            Dimension d = size.get(e.getKey());
            // 좌우로 우회하는 관계선(박스에서 최대 ~60px 바깥)도 잘리지 않도록 여백을 포함한다
            Rectangle2D t = new Rectangle2D.Double(e.getValue().x - 70, e.getValue().y - 10, d.width + 140, d.height + 20);
            r = r == null ? t : r.createUnion(t);
        }
        return r == null ? new Rectangle2D.Double(0, 0, 400, 300) : r;
    }

    // ---- 크기/보이는 컬럼 ----

    List<ErdModel.Column> visible(ErdModel.Table t) {
        return switch (mode) {
            case NAME -> List.of();
            case KEYS -> t.columns.stream().filter(c -> t.pk.contains(c.name()) || t.fk.contains(c.name())).toList();
            case ALL -> t.columns.size() > MAX_ROWS ? t.columns.subList(0, MAX_ROWS) : t.columns;
        };
    }

    private int hidden(ErdModel.Table t) { return mode == Mode.ALL ? Math.max(0, t.columns.size() - MAX_ROWS) : 0; }

    private Font base() {
        Font f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    private Font nameFont() { return base().deriveFont(12.5f); }
    private Font typeFont() { return base().deriveFont(11f); }
    private Font headerFont() { return base().deriveFont(Font.BOLD, 13f); }

    private void computeSizes() {
        size.clear();
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        FontMetrics fn = g.getFontMetrics(nameFont()), ft = g.getFontMetrics(typeFont()), fh = g.getFontMetrics(headerFont());
        for (ErdModel.Table t : model.tables.values()) {
            List<ErdModel.Column> cols = visible(t);
            int nameW = 0, typeW = 0, more = hidden(t);
            for (ErdModel.Column c : cols) { nameW = Math.max(nameW, fn.stringWidth(c.name())); typeW = Math.max(typeW, ft.stringWidth(c.type())); }
            if (more > 0) nameW = Math.max(nameW, fn.stringWidth("… " + more + "개 더"));
            int w = Math.max(Math.max(170, fh.stringWidth(title(t)) + 2 * PAD), PAD + BADGE_W + nameW + 18 + typeW + PAD);
            int rows = cols.size() + (more > 0 ? 1 : 0);
            size.put(t.oid, new Dimension(w, HEADER_H + rows * ROW_H + (rows > 0 ? 6 : 0)));
        }
        g.dispose();
    }

    private static String title(ErdModel.Table t) { return t.external ? t.id() : t.name; }

    // ---- 색 ----

    private static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static Color accent() { return color("Component.accentColor", new Color(0x4A88C7)); }
    private static Color canvasBg() { return color("Panel.background", new Color(0xF2F2F2)); }
    private static Color boxBg() { return color("Table.background", Color.WHITE); }
    private static Color fg() { return color("Label.foreground", Color.BLACK); }
    private static Color border() { return color("Component.borderColor", Color.GRAY); }
    private static Color muted() { Color f = fg(); return new Color(f.getRed(), f.getGreen(), f.getBlue(), 130); }

    // ---- 그리기 ----

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(canvasBg());
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (model.tables.isEmpty()) {
            g2.setColor(muted());
            g2.setFont(base().deriveFont(14f));
            g2.drawString(emptyMessage, 24, 36);
        } else {
            g2.translate(ox, oy);
            g2.scale(scale, scale);
            paintWorld(g2, true);
        }
        g2.dispose();
    }

    BufferedImage render(double factor) {
        Rectangle2D b = worldBounds();
        int m = 30, w = (int) Math.ceil((b.getWidth() + 2 * m) * factor), h = (int) Math.ceil((b.getHeight() + 2 * m) * factor);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(canvasBg());
        g.fillRect(0, 0, w, h);
        g.scale(factor, factor);
        g.translate(m - b.getX(), m - b.getY());
        paintWorld(g, false);
        g.dispose();
        return img;
    }

    void exportPng(File f) throws IOException { ImageIO.write(render(2.0), "png", f); }

    private Set<Long> related(long oid) {
        Set<Long> s = new HashSet<>(Set.of(oid));
        for (ErdModel.Fk f : visibleFks()) {
            if (f.child() == oid) s.add(f.parent());
            if (f.parent() == oid) s.add(f.child());
        }
        return s;
    }

    private void paintWorld(Graphics2D g, boolean interactive) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Long sel = interactive ? selected : null;
        Set<Long> rel = sel != null ? related(sel) : null;
        List<ErdModel.Fk> fks = visibleFks();
        Map<ErdModel.Fk, Route> routes = planRoutes(fks);
        for (int pass = 0; pass < 2; pass++)   // 강조된 관계선은 맨 위에 그린다
            for (ErdModel.Fk f : fks) {
                boolean hot = sel != null && (f.child() == sel || f.parent() == sel);
                if (hot == (pass == 1)) drawEdge(g, f, routes.get(f), hot, rel == null || hot);
            }
        for (Long id : order) drawTable(g, model.tables.get(id), rel == null || rel.contains(id), id.equals(sel));
    }

    private double anchorY(ErdModel.Table t, List<String> cols) {
        Point p = pos.get(t.oid);
        List<ErdModel.Column> vis = visible(t);
        for (String n : cols)
            for (int i = 0; i < vis.size(); i++) if (vis.get(i).name().equals(n)) return p.y + HEADER_H + 3 + i * ROW_H + ROW_H / 2.0;
        return p.y + HEADER_H / 2.0;
    }

    /** 관계선 경로와 양 끝 마커 방향. */
    private record Route(Path2D path, double ax, double ay, int da, double bx, double by, int db) {}

    private record Cand(double ax, int da, double bx, int db, double x, boolean detour) {}

    /**
     * 모든 관계선의 경로를 계획한다. 후보 경로(두 박스 사이 통로, 또는 바깥으로 우회)를 여러 개 만들어
     * 다른 박스를 가로지르는 경로는 크게 감점하고, 이미 쓰는 세로 통로와 겹치면 감점해 옆 통로로 비켜 가게 한다.
     * 같은 부모 컬럼으로 들어오는 선은 끝점을 조금씩 어긋나게 해서 마커가 한 점에 겹치지 않게 한다.
     */
    private Map<ErdModel.Fk, Route> planRoutes(List<ErdModel.Fk> fks) {
        Map<Long, Rectangle2D> boxes = new HashMap<>();
        for (var e : pos.entrySet()) {
            Dimension d = size.get(e.getKey());
            boxes.put(e.getKey(), new Rectangle2D.Double(e.getValue().x - 4, e.getValue().y - 4, d.width + 8, d.height + 8));
        }
        Map<String, Integer> count = new HashMap<>(), seen = new HashMap<>();
        for (ErdModel.Fk f : fks) count.merge(f.parent() + ":" + anchorY(model.tables.get(f.parent()), f.parentCols()), 1, Integer::sum);

        List<double[]> verticals = new ArrayList<>();   // {x, y1, y2}
        Map<ErdModel.Fk, Route> out = new HashMap<>();
        for (ErdModel.Fk f : fks) {
            ErdModel.Table c = model.tables.get(f.child()), p = model.tables.get(f.parent());
            Point cp = pos.get(c.oid), pp = pos.get(p.oid);
            Dimension cs = size.get(c.oid), ps = size.get(p.oid);
            double ay = anchorY(c, f.childCols());
            double baseBy = anchorY(p, f.parentCols());
            String key = f.parent() + ":" + baseBy;
            int n = count.get(key), i = seen.merge(key, 1, Integer::sum) - 1;
            double by = baseBy + (n > 1 ? Math.max(-8, Math.min(8, (i - (n - 1) / 2.0) * 6)) : 0);
            double cl = cp.x, cr = cp.x + cs.width, pl = pp.x, pr = pp.x + ps.width;

            List<Cand> cands = new ArrayList<>();
            if (cr + 2 * STUB <= pl) for (double x : channels(cr + STUB, pl - STUB, boxes, c.oid, p.oid)) cands.add(new Cand(cr, 1, pl, -1, x, false));
            else if (pr + 2 * STUB <= cl) for (double x : channels(pr + STUB, cl - STUB, boxes, c.oid, p.oid)) cands.add(new Cand(cl, -1, pr, 1, x, false));
            for (int k = 0; k < 4; k++) {   // 바깥으로 우회 (자기 참조 포함)
                cands.add(new Cand(cr, 1, pr, 1, Math.max(cr, pr) + 28 + 8 * k, true));
                cands.add(new Cand(cl, -1, pl, -1, Math.min(cl, pl) - 28 - 8 * k, true));
            }

            Cand best = null;
            double bestCost = Double.MAX_VALUE;
            for (Cand k : cands) {
                double cost = Math.abs(k.x() - k.ax()) + Math.abs(ay - by) + Math.abs(k.bx() - k.x()) + (k.detour() ? 150 : 0);
                Line2D s1 = new Line2D.Double(k.ax(), ay, k.x(), ay), s2 = new Line2D.Double(k.x(), ay, k.x(), by), s3 = new Line2D.Double(k.x(), by, k.bx(), by);
                for (var b : boxes.entrySet()) {
                    if (b.getKey() == c.oid || b.getKey() == p.oid) continue;
                    if (b.getValue().intersectsLine(s1) || b.getValue().intersectsLine(s2) || b.getValue().intersectsLine(s3)) cost += 10000;
                }
                for (double[] v : verticals)
                    if (Math.abs(v[0] - k.x()) < 5 && v[1] < Math.max(ay, by) && v[2] > Math.min(ay, by)) cost += 400;
                if (cost < bestCost) { bestCost = cost; best = k; }
            }
            Path2D path = new Path2D.Double();
            path.moveTo(best.ax(), ay);
            path.lineTo(best.x(), ay);
            path.lineTo(best.x(), by);
            path.lineTo(best.bx(), by);
            verticals.add(new double[]{best.x(), Math.min(ay, by), Math.max(ay, by)});
            out.put(f, new Route(path, best.ax(), ay, best.da(), best.bx(), by, best.db()));
        }
        return out;
    }

    /** 두 박스 사이 [lo, hi] 안의 세로 통로 후보: 중앙, 중앙에서 좌우로 비킨 위치, 다른 박스 옆 빈 곳. */
    private static List<Double> channels(double lo, double hi, Map<Long, Rectangle2D> boxes, long c, long p) {
        java.util.LinkedHashSet<Double> xs = new java.util.LinkedHashSet<>();
        double mid = (lo + hi) / 2;
        xs.add(mid);
        for (int k = 1; k <= 4; k++) { xs.add(mid + 8 * k); xs.add(mid - 8 * k); }
        for (var b : boxes.entrySet()) {
            if (b.getKey() == c || b.getKey() == p) continue;
            xs.add(b.getValue().getMaxX() + 12);
            xs.add(b.getValue().getMinX() - 12);
        }
        List<Double> out = new ArrayList<>();
        for (double x : xs) if (x >= lo && x <= hi) out.add(x);
        return out.isEmpty() ? List.of(mid) : out;
    }

    private void drawEdge(Graphics2D g, ErdModel.Fk f, Route r, boolean hot, boolean on) {
        ErdModel.Table c = model.tables.get(f.child());
        double ax = r.ax(), ay = r.ay(), bx = r.bx(), by = r.by();
        int da = r.da(), db = r.db();
        Path2D path = r.path();
        Composite old = g.getComposite();
        if (!on) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
        Style style = styleOf(f);
        int alpha = style == Style.DOTTED ? 105 : 150;
        Color line = hot ? accent() : new Color(fg().getRed(), fg().getGreen(), fg().getBlue(), alpha);
        g.setColor(line);
        float w = hot ? 2.2f : 1.3f;
        float[] dash = switch (style) { case SOLID -> null; case DASHED -> new float[]{9f, 6f}; case DOTTED -> new float[]{2.5f, 5f}; };
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.draw(path);
        g.setStroke(new BasicStroke(hot ? 2f : 1.3f));
        // 자식(다) 쪽: 까마귀발 + (필수면 막대 / 선택이면 원)
        g.draw(new Line2D.Double(ax + da * 12, ay, ax, ay - 6));
        g.draw(new Line2D.Double(ax + da * 12, ay, ax, ay));
        g.draw(new Line2D.Double(ax + da * 12, ay, ax, ay + 6));
        boolean nullable = f.childCols().stream().map(c::column).anyMatch(col -> col != null && !col.notNull());
        if (nullable) {
            g.setColor(boxBg());
            g.fill(new java.awt.geom.Ellipse2D.Double(ax + da * 18 - 4, ay - 4, 8, 8));
            g.setColor(line);
            g.draw(new java.awt.geom.Ellipse2D.Double(ax + da * 18 - 4, ay - 4, 8, 8));
        } else g.draw(new Line2D.Double(ax + da * 17, ay - 5, ax + da * 17, ay + 5));
        // 부모(일) 쪽: 막대 두 개
        g.draw(new Line2D.Double(bx + db * 8, by - 6, bx + db * 8, by + 6));
        g.draw(new Line2D.Double(bx + db * 13, by - 6, bx + db * 13, by + 6));
        g.setComposite(old);
    }

    private void drawTable(Graphics2D g, ErdModel.Table t, boolean on, boolean sel) {
        Point p = pos.get(t.oid);
        Dimension d = size.get(t.oid);
        Composite old = g.getComposite();
        if (!on) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        RoundRectangle2D box = new RoundRectangle2D.Double(p.x, p.y, d.width, d.height, 10, 10);
        g.setColor(new Color(0, 0, 0, 38));
        g.fill(new RoundRectangle2D.Double(p.x + 2, p.y + 3, d.width, d.height, 10, 10));
        g.setColor(boxBg());
        g.fill(box);
        Shape oldClip = g.getClip();
        g.clip(box);
        g.setColor(t.external ? new Color(0x7B8794) : accent());
        g.fillRect(p.x, p.y, d.width, HEADER_H);
        g.setClip(oldClip);

        g.setFont(headerFont());
        g.setColor(Color.WHITE);
        FontMetrics fh = g.getFontMetrics();
        g.drawString(title(t), p.x + PAD, p.y + (HEADER_H + fh.getAscent() - fh.getDescent()) / 2);

        List<ErdModel.Column> cols = visible(t);
        Font nf = nameFont(), tf = typeFont(), bf = base().deriveFont(Font.BOLD, 10f);
        FontMetrics fn = g.getFontMetrics(nf), ft = g.getFontMetrics(tf);
        for (int i = 0; i < cols.size(); i++) {
            ErdModel.Column col = cols.get(i);
            int y0 = p.y + HEADER_H + 3 + i * ROW_H;
            int base = y0 + (ROW_H + fn.getAscent() - fn.getDescent()) / 2;
            boolean pk = t.pk.contains(col.name()), fk = t.fk.contains(col.name()), inf = t.inferred.contains(col.name());
            if (pk || fk || inf) {
                g.setFont(bf);
                g.setColor(pk ? new Color(0xD9A400) : fk ? new Color(0x6C8EBF) : new Color(0x6C8EBF, true));
                if (!pk && !fk) g.setColor(new Color(108, 142, 191, 130));
                g.drawString(pk ? "PK" : fk ? "FK" : "fk?", p.x + PAD, base);
            }
            g.setFont(nf);
            g.setColor(fg());
            g.drawString(col.name(), p.x + PAD + BADGE_W, base);
            g.setFont(tf);
            g.setColor(muted());
            g.drawString(col.type(), p.x + d.width - PAD - ft.stringWidth(col.type()), y0 + (ROW_H + ft.getAscent() - ft.getDescent()) / 2);
        }
        int more = hidden(t);
        if (more > 0) {
            g.setFont(nf);
            g.setColor(muted());
            int y0 = p.y + HEADER_H + 3 + cols.size() * ROW_H;
            g.drawString("… " + more + "개 더", p.x + PAD + BADGE_W, y0 + (ROW_H + fn.getAscent() - fn.getDescent()) / 2);
        }
        g.setColor(sel ? accent() : border());
        g.setStroke(new BasicStroke(sel ? 2.4f : 1f));
        g.draw(box);
        g.setComposite(old);
    }

    // ---- 조작 ----

    private Point2D toWorld(Point s) { return new Point2D.Double((s.x - ox) / scale, (s.y - oy) / scale); }

    /** 화면 좌표의 테이블(맨 위 것). 없으면 null. */
    ErdModel.Table tableAt(Point screen) {
        Point2D w = toWorld(screen);
        for (int i = order.size() - 1; i >= 0; i--) {
            Long id = order.get(i);
            Point p = pos.get(id);
            Dimension d = size.get(id);
            if (new Rectangle(p.x, p.y, d.width, d.height).contains(w)) return model.tables.get(id);
        }
        return null;
    }

    private void popup(MouseEvent e, ErdModel.Table t) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem open = new JMenuItem("Select top 100 (콘솔)");
        open.addActionListener(x -> onOpen.accept(t));
        JMenuItem copy = new JMenuItem("테이블 이름 복사");
        copy.addActionListener(x -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Ddl.qualified(t.schema, t.name)), null));
        m.add(open);
        m.add(copy);
        m.show(this, e.getX(), e.getY());
    }

    @Override public String getToolTipText(MouseEvent e) {
        ErdModel.Table t = tableAt(e.getPoint());
        if (t == null) return null;
        Point2D w = toWorld(e.getPoint());
        Point p = pos.get(t.oid);
        int idx = (int) Math.floor((w.getY() - p.y - HEADER_H - 3) / ROW_H);
        List<ErdModel.Column> vis = visible(t);
        if (idx < 0 || idx >= vis.size()) return "<html><b>" + esc(t.id()) + "</b> · 컬럼 " + t.columns.size() + "개</html>";
        ErdModel.Column col = vis.get(idx);
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(col.name())).append("</b> ").append(esc(col.type()));
        sb.append(col.notNull() ? " · NOT NULL" : " · NULL 허용");
        if (t.pk.contains(col.name())) sb.append(" · PK");
        for (ErdModel.Fk f : visibleFks())
            if (f.child() == t.oid && f.childCols().contains(col.name())) {
                ErdModel.Table pt = model.tables.get(f.parent());
                sb.append(f.inferred() ? "<br>논리 관계(추정, FK 없음) → " : f.identifying() ? "<br>FK(식별) → " : "<br>FK(비식별) → ").append(esc(pt.id())).append('(').append(esc(String.join(", ", f.parentCols()))).append(')');
            }
        return sb.append("</html>").toString();
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
