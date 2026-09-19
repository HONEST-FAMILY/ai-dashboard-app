package com.honestfamily.dashboard.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class TokenListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH_TOKEN) continue
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val token = dataMap.getString("token")
            if (!token.isNullOrBlank()) WatchStore.saveToken(this, token)
        }
    }

    companion object {
        const val PATH_TOKEN = "/hf/token"
    }
}
