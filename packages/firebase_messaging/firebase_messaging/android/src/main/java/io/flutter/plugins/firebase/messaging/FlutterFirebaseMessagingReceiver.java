// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.firebase.messaging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.google.firebase.messaging.RemoteMessage;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class FlutterFirebaseMessagingReceiver extends BroadcastReceiver {
  private static final String TAG = "FLTFireMsgReceiver";
  static HashMap<String, RemoteMessage> notifications = new HashMap<>();
  private static boolean suspendNotification = false;
  private static Map<String, String> suspendNotificationMessageFilters;

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.d(TAG, "broadcast received for message");
    if (ContextHolder.getApplicationContext() == null) {
      ContextHolder.setApplicationContext(context.getApplicationContext());
    }

    if (intent.getExtras() == null) {
      Log.d(
          TAG,
          "broadcast received but intent contained no extras to process RemoteMessage. Operation cancelled.");
      return;
    }

    RemoteMessage remoteMessage = new RemoteMessage(intent.getExtras());

    // Store the RemoteMessage if the message contains a notification payload.
    if (remoteMessage.getNotification() != null) {
      notifications.put(remoteMessage.getMessageId(), remoteMessage);
      FlutterFirebaseMessagingStore.getInstance().storeFirebaseMessage(remoteMessage);
    }

    if (shouldSuspendNotification(context, remoteMessage)) {
      Log.i(TAG, "Notification is suspended.");
      return;
    }

    //  |-> ---------------------
    //      App in Foreground
    //   ------------------------
    if (FlutterFirebaseMessagingUtils.isApplicationForeground(context)) {
      FlutterFirebaseRemoteMessageLiveData.getInstance().postRemoteMessage(remoteMessage);
      return;
    }

    //  |-> ---------------------
    //    App in Background/Quit
    //   ------------------------
    Intent onBackgroundMessageIntent =
        new Intent(context, FlutterFirebaseMessagingBackgroundService.class);
    onBackgroundMessageIntent.putExtra(
        FlutterFirebaseMessagingUtils.EXTRA_REMOTE_MESSAGE, remoteMessage);
    FlutterFirebaseMessagingBackgroundService.enqueueMessageProcessing(
        context, onBackgroundMessageIntent);
  }

  public static void setSuspendNotification(boolean suspend, Map<String, String> filters) {
    suspendNotification = suspend;
    suspendNotificationMessageFilters = filters;
    Log.i(TAG, suspend ? "Notification is suspended": "Notification is resumed");
  }

  /**
   * This method is used whether to suspend the braze notification based on the message filter given
   * by caller.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   * @return Wether the message is suspended or not
   */
  private Boolean shouldSuspendNotification(Context context, final RemoteMessage remoteMessage) {
    if (FlutterFirebaseMessagingUtils.isApplicationForeground(context)
        && suspendNotification) {

      final Map<String, String> remoteMessageData = remoteMessage.getData();

      if (remoteMessageData == null || remoteMessageData.isEmpty()) {
        Log.i(TAG, "Remote message data from FCM was null. returning ...");
        return true;
      }

      if (suspendNotificationMessageFilters != null
          && !suspendNotificationMessageFilters.isEmpty()) {
        for (String key : suspendNotificationMessageFilters.keySet()) {
          final String val = suspendNotificationMessageFilters.get(key);
          if (remoteMessageData.containsKey(key) && val != null && !val.isEmpty()) {
            final String data = remoteMessageData.get(key);
            if (data != null && data.contains(val)) {
              return true;
            }
          }
        }
      } else {
        return true;
      }
    }

    return false;
  }

  private void printRemoteMessageData(final RemoteMessage remoteMessage) {
     final Map<String, String> remoteMessageData = remoteMessage.getData();

     if (remoteMessage.getNotification() != null) {
       Log.i(TAG, "Remote message contains notification");
       Log.i(TAG, "Remote notification body: " + remoteMessage.getNotification().getBody());
     }

     if (remoteMessageData == null || remoteMessageData.isEmpty()) {
       Log.i(TAG, "Remote message data from FCM was null. returning ...");
       return;
     }

     for (String key : remoteMessageData.keySet()) {
      final String val = remoteMessageData.get(key);
      if (val != null && !val.isEmpty()) {
         Log.i(TAG, "Remote message data key: " + key + " value: " + val);
      }
    }
  }
}
