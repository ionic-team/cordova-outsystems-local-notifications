/*
 * OutSystems Cordova plugin — Local Notifications.
 *
 * Exposes the SAME public API as @capacitor/local-notifications for the shared
 * methods (schedule, update, cancel, cancelAll, removeDeliveredNotifications,
 * removeDeliveredNotificationsById, removeAllDeliveredNotifications, getByIds,
 * getAll) plus the localNotificationReceived / localNotificationActionPerformed
 * events. Options and return shapes are identical to the Capacitor plugin, so
 * the OutSystems Mobile Library bridges each client action to a single method.
 */
var exec = require('cordova/exec');

var PLUGIN_NAME = 'OSLocalNotificationsPlugin';

// eventName -> array of listener callbacks
var listeners = {};
var eventChannelStarted = false;

function promisify(action, args) {
  return new Promise(function (resolve, reject) {
    exec(
      function (result) {
        resolve(result);
      },
      function (error) {
        reject(error);
      },
      PLUGIN_NAME,
      action,
      args
    );
  });
}

/**
 * Open a single persistent (keepCallback) channel to native and fan events out
 * to the registered JS listeners. Native pushes { eventName, data } objects.
 */
function ensureEventChannel() {
  if (eventChannelStarted) {
    return;
  }
  eventChannelStarted = true;
  exec(
    function (payload) {
      if (!payload || !payload.eventName) {
        return;
      }
      var callbacks = listeners[payload.eventName] || [];
      for (var i = 0; i < callbacks.length; i++) {
        callbacks[i](payload.data);
      }
    },
    function () {},
    PLUGIN_NAME,
    'startEventListener',
    []
  );
}

module.exports = {
  schedule: function (options) {
    return promisify('schedule', [options || {}]);
  },
  update: function (options) {
    return promisify('update', [options || {}]);
  },
  cancel: function (options) {
    return promisify('cancel', [options || {}]);
  },
  cancelAll: function () {
    return promisify('cancelAll', []);
  },
  removeDeliveredNotifications: function (delivered) {
    return promisify('removeDeliveredNotifications', [delivered || {}]);
  },
  removeDeliveredNotificationsById: function (options) {
    return promisify('removeDeliveredNotificationsById', [options || {}]);
  },
  removeAllDeliveredNotifications: function () {
    return promisify('removeAllDeliveredNotifications', []);
  },
  getByIds: function (options) {
    return promisify('getByIds', [options || {}]);
  },
  getAll: function (options) {
    return promisify('getAll', [options || {}]);
  },
  addListener: function (eventName, callback) {
    listeners[eventName] = listeners[eventName] || [];
    listeners[eventName].push(callback);
    ensureEventChannel();
    return Promise.resolve({
      remove: function () {
        listeners[eventName] = (listeners[eventName] || []).filter(function (cb) {
          return cb !== callback;
        });
        return Promise.resolve();
      }
    });
  },
  removeAllListeners: function () {
    listeners = {};
    return Promise.resolve();
  }
};
