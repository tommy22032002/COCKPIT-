package fr.tom.cockpit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("id");
        Reminders.show(ctx, id == null ? "rappel" : id, intent.getStringExtra("title"),
                intent.getStringExtra("body"), intent.getStringExtra("hash"));
    }
}
