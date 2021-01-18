// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.firebase.messaging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
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

    RemoteMessage remoteMessage = new RemoteMessage(intent.getExtras());

    // Store the RemoteMessage if the message contains a notification payload.
    if (remoteMessage.getNotification() != null) {
      notifications.put(remoteMessage.getMessageId(), remoteMessage);
      FlutterFirebaseMessagingStore.getInstance().storeFirebaseMessage(remoteMessage);
    }

    if (shouldSuspendBrazeNotification(context, remoteMessage)) {
      Log.i(TAG, "Braze message detected! but notification is suspended.");
      return;
    }

    if (isHandledByBraze(context, remoteMessage)) {
      Log.i(TAG, "Braze message detected! returning...");
      return;
    }

    //  |-> ---------------------
    //      App in Foreground
    //   ------------------------
    if (FlutterFirebaseMessagingUtils.isApplicationForeground(context)) {
      Intent onMessageIntent = new Intent(FlutterFirebaseMessagingUtils.ACTION_REMOTE_MESSAGE);
      onMessageIntent.putExtra(FlutterFirebaseMessagingUtils.EXTRA_REMOTE_MESSAGE, remoteMessage);
      LocalBroadcastManager.getInstance(context).sendBroadcast(onMessageIntent);
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
    Log.i(TAG, suspend ? "Braze message suspended": "resumed");
  }

  /**
   * This method is used to handle braze related messages via reflection.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   * @return Wether the message was handled by braze or not.
   */
  private Boolean isHandledByBraze(Context context, RemoteMessage remoteMessage) {
    Method method;
    Boolean isHandled = false;

    try {
      Class brazeMessagingService = Class.forName("com.appboy.AppboyFirebaseMessagingService");
      method =
          brazeMessagingService.getMethod(
              "handleBrazeRemoteMessage", Context.class, RemoteMessage.class);

      isHandled = (Boolean) method.invoke(brazeMessagingService, context, remoteMessage);

    } catch (NoSuchMethodException noSuchMethodException) {
      Log.e(TAG, "method doesn't exist", noSuchMethodException);
    } catch (Exception exception) {
      Log.e(TAG, "something went wrong", exception);
    }

    return isHandled;
  }

  /**
   * This method is used to check that remote message is braze related messages via reflection.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   * @return Wether the message is for braze or not.
   */
  private Boolean isBrazePushNotification(RemoteMessage remoteMessage) {
    Method method;
    Boolean isBraze = false;

    try {
      Class brazeMessagingService = Class.forName("com.appboy.AppboyFirebaseMessagingService");
      method = brazeMessagingService.getMethod("isBrazePushNotification", RemoteMessage.class);
      isBraze = (Boolean) method.invoke(brazeMessagingService, remoteMessage);
    } catch (NoSuchMethodException noSuchMethodException) {
      Log.e(TAG, "method doesn't exist", noSuchMethodException);
    } catch (Exception exception) {
      Log.e(TAG, "something went wrong", exception);
    }

    return isBraze;
  }

  /**
   * This method is used whether to suspend the braze notification based on the message filter given
   * by caller.
   *
   * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
   * @return Wether the message is suspended or not
   */
  private Boolean shouldSuspendBrazeNotification(Context context, final RemoteMessage remoteMessage) {
    if (isBrazePushNotification(remoteMessage)
        && FlutterFirebaseMessagingUtils.isApplicationForeground(context)
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
