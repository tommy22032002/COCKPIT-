package fr.tom.cockpit.wear;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/** Reçoit le résumé du PEA envoyé par le téléphone. */
public class DataListener extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() != DataEvent.TYPE_CHANGED || !"/pea".equals(e.getDataItem().getUri().getPath())) continue;
            String d = DataMapItem.fromDataItem(e.getDataItem()).getDataMap().getString("d");
            if (d != null && d.length() > 2) Pea.save(this, d, true);
        }
    }
}
