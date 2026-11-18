package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Fournit les lignes du widget « Capital · Complet ». */
public class FullService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
    }

    static class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private final int id;
        private List<FullRows.Row> rows = new ArrayList<>();
        private JSONObject data = new JSONObject();

        Factory(Context c, int widgetId) { ctx = c; id = widgetId; }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            try {
                String s = Prefs.widgetJson(ctx);
                data = s != null && s.length() > 2 ? new JSONObject(s) : new JSONObject();
            } catch (Exception e) { data = new JSONObject(); }
            try { rows = FullRows.build(data); } catch (Exception e) { rows = new ArrayList<>(); }
        }

        @Override public void onDestroy() { rows.clear(); }

        @Override public int getCount() { return rows.size(); }

        @Override
        public RemoteViews getViewAt(int i) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_full_item);
            if (i < 0 || i >= rows.size()) return v;
            FullRows.Row r = rows.get(i);
            int wDp = 340;
            try {
                int[] wh = More.size(ctx, AppWidgetManager.getInstance(ctx), id);
                if (wh != null && wh[0] > 0) wDp = wh[0] - 12;
            } catch (Exception ignored) { }
            try {
                Bitmap b = FullRows.draw(ctx, r, data, Math.max(200, Math.min(600, wDp)));
                v.setImageViewBitmap(R.id.f_img, b);
            } catch (Exception ignored) { }
            Intent fill = new Intent();
            fill.putExtra("hash", r.hash == null ? "" : r.hash);
            v.setOnClickFillInIntent(R.id.f_img, fill);
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return false; }
    }
}
