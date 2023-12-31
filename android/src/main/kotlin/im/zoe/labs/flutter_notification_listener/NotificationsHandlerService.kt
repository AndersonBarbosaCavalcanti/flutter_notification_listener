package im.zoe.labs.flutter_notification_listener

import NotificationDbHelper
import NotificationTmpDbHelper
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class NotificationsHandlerService: MethodChannel.MethodCallHandler, NotificationListenerService() {
    private val queue = ArrayDeque<NotificationEvent>()
    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context

    // notification event cache: packageName_id -> event
    private val eventsCache = HashMap<String, NotificationEvent>()

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
      when (call.method) {
          "service.initialized" -> {
              initFinish()
              return result.success(true)
          }
          // this should move to plugin
          "service.promoteToForeground" -> {
              // add data
              val cfg = Utils.PromoteServiceConfig.fromMap(call.arguments as Map<*, *>).apply {
                  foreground = true
              }
              return result.success(promoteToForeground(cfg))
          }
          "service.demoteToBackground" -> {
              return result.success(demoteToBackground())
          }
          "service.tap" -> {
              // tap the notification
              Log.d(TAG, "tap the notification")
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              return result.success(tapNotification(uid))
          }
          "service.tap_action" -> {
              // tap the action
              Log.d(TAG, "tap action of notification")
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              val idx = args[1]!! as Int
              return result.success(tapNotificationAction(uid, idx))
          }
          "service.send_input" -> {
              // send the input data
              Log.d(TAG, "set the content for input and the send action")
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              val idx = args[1]!! as Int
              val data = args[2]!! as Map<*, *>
              return result.success(sendNotificationInput(uid, idx, data))
          }
          "service.get_full_notification" -> {
              val args = call.arguments<ArrayList<*>?>()
              val uid = args!![0]!! as String
              if (!eventsCache.contains(uid)) {
                  return result.error("notFound", "can't found this notification $uid", "")
              }
              return result.success(Utils.Marshaller.marshal(eventsCache[uid]?.mSbn))
          }
          "service.listNotifications" -> {
                val notifications = listNotifications()
                result.success(notifications) 
            }
            "service.listNotificationsTmp" -> {
                val notifications = listNotificationsTmp()
                result.success(notifications) 
            }
          "service.getNotificationsFromStatusBar" -> {
              val notifications = getNotificationsFromStatusBar()
              result.success(notifications)
          }
            "service.removeNotificationFromLocalDb" -> {
              val args = call.arguments<ArrayList<*>?>()
              val timestamp = args!![0]!! as String
              val text = args!![1]!! as String
              val r = removeNotificationFromLocalDb(timestamp, text)
                result.success(r) 
            }
          else -> {
              Log.d(TAG, "unknown method ${call.method}")
              result.notImplemented()
          }
      }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // if get shutdown release the wake lock
        when (intent?.action) {
            ACTION_SHUTDOWN -> {
                (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                        if (isHeld) release()
                    }
                }
                Log.i(TAG, "stop notification handler service!")
                disableServiceSettings(mContext)
                stopForeground(true)
                stopSelf()
            }
            else -> {

            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        mContext = this

        // store the service instance
        instance = this

        Log.i(TAG, "notification listener service onCreate")
        startListenerService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "notification listener service onDestroy")
        val bdi = Intent(mContext, RebootBroadcastReceiver::class.java)
        // remove notification
        sendBroadcast(bdi)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "notification listener service onTaskRemoved")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        cancelAllNotifications()

        var listNotificationsTmp = listNotificationsTmp();
        for (item in listNotificationsTmp) {
            removeNotificationTmpFromLocalDb("${item["timestamp"]}", "")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        FlutterInjector.instance().flutterLoader().startInitialization(mContext)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(mContext, null)

        val evt = NotificationEvent(mContext, sbn)

        // store the evt to cache
        eventsCache[evt.uid] = evt

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                //Log.d(TAG, "service is not start try to queue the event")
                queue.add(evt)
            } else {
                //Log.d(TAG, "send event to flutter side immediately!")
                Handler(mContext.mainLooper).post { sendEvent(evt) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val evt = NotificationEvent(mContext, sbn)
        // remove the event from cache
        eventsCache.remove(evt.uid)
        removeNotificationTmpFromLocalDb("${evt.data["timestamp"]}", "")
        Log.d(TAG, "notification removed: ${evt.uid}")
    }

    private fun initFinish() {
        //Log.d(TAG, "service's flutter engine initialize finished")
        synchronized(sServiceStarted) {
            while (!queue.isEmpty()) sendEvent(queue.remove())
            sServiceStarted.set(true)
        }
    }

    private fun promoteToForeground(cfg: Utils.PromoteServiceConfig? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "promoteToForeground need sdk >= 26")
            return false
        }

        if (cfg?.foreground != true) {
            Log.i(TAG, "no need to start foreground: ${cfg?.foreground}")
            return false
        }

        // first is not running already, start at first
        if (!FlutterNotificationListenerPlugin.isServiceRunning(mContext, this.javaClass)) {
            Log.e(TAG, "service is not running")
            return false
        }

        // get args from store or args
        val cfg = cfg ?: Utils.PromoteServiceConfig.load(this)
        // make the service to foreground

        // take a wake lock
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // create a channel for notification
        val channel = NotificationChannel(CHANNEL_ID, "Flutter Notifications Listener Plugin", NotificationManager.IMPORTANCE_HIGH)
        val imageId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(cfg.title)
            .setContentText(cfg.description)
            .setShowWhen(cfg.showWhen ?: false)
            .setSubText(cfg.subTitle)
            .setSmallIcon(imageId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        Log.d(TAG, "promote the service to foreground")
        startForeground(ONGOING_NOTIFICATION_ID, notification)

        return true
    }

    private fun demoteToBackground(): Boolean {
        Log.d(TAG, "demote the service to background")
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) release()
            }
        }
        stopForeground(true)
        return true
    }

    private fun tapNotification(uid: String): Boolean {
        Log.d(TAG, "tap the notification: $uid")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid] ?: return false
        n.mSbn.notification.contentIntent.send()
        return true
    }

    private fun tapNotificationAction(uid: String, idx: Int): Boolean {
        Log.d(TAG, "tap the notification action: $uid @$idx")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid]
        if (n == null) {
            Log.e(TAG, "notification is null: $uid")
            return false
        }
        if (n.mSbn.notification.actions.size <= idx) {
            Log.e(TAG, "tap action out of range: size ${n.mSbn.notification.actions.size} index $idx")
            return false
        }

        val act = n.mSbn.notification.actions[idx]
        if (act == null) {
            Log.e(TAG, "notification $uid action $idx not exits")
            return false
        }
        act.actionIntent.send()
        return true
    }

    private fun sendNotificationInput(uid: String, idx: Int, data: Map<*, *>): Boolean {
        Log.d(TAG, "tap the notification action: $uid @$idx")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid]
        if (n == null) {
            Log.e(TAG, "notification is null: $uid")
            return false
        }
        if (n.mSbn.notification.actions.size <= idx) {
            Log.e(TAG, "send inputs out of range: size ${n.mSbn.notification.actions.size} index $idx")
            return false
        }

        val act = n.mSbn.notification.actions[idx]
        if (act == null) {
            Log.e(TAG, "notification $uid action $idx not exits")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            if (act.remoteInputs == null) {
                Log.e(TAG, "notification $uid action $idx remote inputs not exits")
                return false
            }

            val intent = Intent()
            val bundle = Bundle()
            act.remoteInputs.forEach {
                if (data.containsKey(it.resultKey as String)) {
                    Log.d(TAG, "add input content: ${it.resultKey} => ${data[it.resultKey]}")
                    bundle.putCharSequence(it.resultKey, data[it.resultKey] as String)
                }
            }
            RemoteInput.addResultsToIntent(act.remoteInputs, intent, bundle)
            act.actionIntent.send(mContext, 0, intent)
            Log.d(TAG, "send the input action success")
            return true
        } else {
            Log.e(TAG, "not implement :sdk < KITKAT_WATCH")
            return false
        }
    }

    companion object {

        var callbackHandle = 0L

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var instance: NotificationsHandlerService? = null

        @JvmStatic
        private val TAG = "NotificationsListenerService"

        private const val ONGOING_NOTIFICATION_ID = 100
        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"
        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        private const val CHANNEL_ID = "flutter_notifications_listener_channel"

        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        @JvmStatic
        private val sServiceStarted = AtomicBoolean(false)

        private const val BG_METHOD_CHANNEL_NAME = "flutter_notification_listener/bg_method"

        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        private const val ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

        const val NOTIFICATION_INTENT_KEY = "object"
        const val NOTIFICATION_INTENT = "notification_event"

        fun permissionGiven(context: Context): Boolean {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS)
            if (!TextUtils.isEmpty(flat)) {
                val names = flat.split(":").toTypedArray()
                for (name in names) {
                    val componentName = ComponentName.unflattenFromString(name)
                    val nameMatch = TextUtils.equals(packageName, componentName?.packageName)
                    if (nameMatch) {
                        return true
                    }
                }
            }

            return false
        }

        fun openPermissionSettings(context: Context): Boolean {
            context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        fun enableServiceSettings(context: Context) {
            toggleServiceSettings(context, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }

        fun disableServiceSettings(context: Context) {
            toggleServiceSettings(context, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        private fun toggleServiceSettings(context: Context, state: Int) {
            val receiver = ComponentName(context, NotificationsHandlerService::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
        }

        fun updateFlutterEngine(context: Context) {
            //Log.d(TAG, "call instance update flutter engine from plugin init")
            instance?.updateFlutterEngine(context)
            // we need to `finish init` manually
            instance?.initFinish()
        }
    }

    private fun getFlutterEngine(context: Context): FlutterEngine {
        var eng = FlutterEngineCache.getInstance().get(FlutterNotificationListenerPlugin.FLUTTER_ENGINE_CACHE_KEY)
        if (eng != null) return eng

        Log.i(TAG, "flutter engine cache is null, create a new one")
        eng = FlutterEngine(context)

        // ensure initialization
        FlutterInjector.instance().flutterLoader().startInitialization(context)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context, arrayOf())

        // call the flutter side init
        // get the call back handle information
        val cb = context.getSharedPreferences(FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .getLong(FlutterNotificationListenerPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)

        if (cb != 0L) {
            Log.d(TAG, "try to find callback: $cb")
            val info = FlutterCallbackInformation.lookupCallbackInformation(cb)
            val args = DartExecutor.DartCallback(context.assets,
                FlutterInjector.instance().flutterLoader().findAppBundlePath(), info)
            // call the callback
            eng.dartExecutor.executeDartCallback(args)
        } else {
            Log.e(TAG, "Fatal: no callback register")
        }

        FlutterEngineCache.getInstance().put(FlutterNotificationListenerPlugin.FLUTTER_ENGINE_CACHE_KEY, eng)
        return eng
    }

    private fun updateFlutterEngine(context: Context) {
        //Log.d(TAG, "update the flutter engine of service")
        // take the engine
        val eng = getFlutterEngine(context)
        sBackgroundFlutterEngine = eng

        // set the method call
        mBackgroundChannel = MethodChannel(eng.dartExecutor.binaryMessenger, BG_METHOD_CHANNEL_NAME)
        mBackgroundChannel.setMethodCallHandler(this)
    }

    private fun startListenerService(context: Context) {
        Log.d(TAG, "start listener service")
        synchronized(sServiceStarted) {
            // promote to foreground
            // TODO: take from intent, currently just load form store
            promoteToForeground(Utils.PromoteServiceConfig.load(context))

            // we should to update
            Log.d(TAG, "service's flutter engine is null, should update one")
            updateFlutterEngine(context)

            sServiceStarted.set(true)
        }
        Log.d(TAG, "service start finished")
    }

    private fun sendEvent(evt: NotificationEvent) {
        //Log.d(TAG, "send notification event: ...")
        Log.d(TAG, "send notification event: ${evt.data}")

        var listNotificationsTmp = listNotificationsTmp();
        var totTmp = 0;
        var totStatusBar = 0;

        println("----listNotificationsTmp---")
        for (item in listNotificationsTmp) {
            println("titleItem==${item["title"]}==")
            println("titleEvt==${evt.data["title"]}==")
            println("textItem==${item["text"]}==")
            println("textEvt==${evt.data["text"]}==")
            println("bigTextItem==${item["bigText"]}==")
            println("bigTextEvt==${evt.data["bigText"]}==")
            println("-------")
            if(
                item["title"] == evt.data["title"] &&
                item["package_name"] == evt.data["package_name"] &&
                item["text"] == evt.data["text"]
            ) {
                if(evt.data["bigText"] != null && item["bigText"] == evt.data["bigText"]) {
                    totTmp += 1;
                } else if(evt.data["bigText"] == null && item["bigText"] == "") {
                    totTmp += 1;
                } else if(item["bigText"] == evt.data["bigText"]) {
                    totTmp += 1;
                }
            }
        }
        println("----listNotificationsTmp---")
        println("----listNotificationsStatusBar---")

        var listNotificationsStatusBar = getNotificationsFromStatusBar();
        for (item in listNotificationsStatusBar) {
            println("titleItem==${item["title"]}==")
            println("titleEvt==${evt.data["title"]}==")
            println("textItem==${item["text"]}==")
            println("textEvt==${evt.data["text"]}==")
            println("bigTextItem==${item["bigText"]}==")
            println("bigTextEvt==${evt.data["bigText"]}==")
            println("-------")
            if(
                item["title"] == evt.data["title"] &&
                item["package_name"] == evt.data["package_name"] &&
                item["text"] == evt.data["text"]
            ) {
                if(evt.data["bigText"] != null && item["bigText"] == evt.data["bigText"]) {
                    totStatusBar += 1;
                } else if(evt.data["bigText"] == null && item["bigText"] == "") {
                    totStatusBar += 1;
                } else if(item["bigText"] == evt.data["bigText"]) {
                    totStatusBar += 1;
                }
            }
        }
        println("----listNotificationsStatusBar---")

        println("=====")
        println(totTmp)
        println(totStatusBar)
        println("=====")

        if(totTmp == 0) {
            for (item in listNotificationsTmp) {
                if(
                    item["title"] == evt.data["title"] &&
                    item["package_name"] == evt.data["package_name"] &&
                    item["text"] == evt.data["text"]
                ) {
                    if(evt.data["bigText"] != null && item["bigText"] == evt.data["bigText"]) {
                        removeNotificationFromLocalDb("${item["timestamp"]}", "")
                    } else if(evt.data["bigText"] == null && item["bigText"] == "") {
                        removeNotificationFromLocalDb("${item["timestamp"]}", "")
                    } else if(item["bigText"] == evt.data["bigText"]) {
                        removeNotificationFromLocalDb("${item["timestamp"]}", "")
                    }
                }
            }
        }

        if(totTmp < totStatusBar || totTmp == 0) {
            saveNotificationToLocalDb(evt.data as Map<String, String>)
            saveNotificationTmpToLocalDb(evt.data as Map<String, String>)
        }

        callbackHandle = mContext.getSharedPreferences(FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .getLong(FlutterNotificationListenerPlugin.CALLBACK_HANDLE_KEY, 0)

        // why mBackgroundChannel can be null?

        try {
            // don't care about the method name
            mBackgroundChannel.invokeMethod("sink_event", listOf(callbackHandle, evt.data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveNotificationToLocalDb(notificationData: Map<String, String>) {
        val dbHelper = NotificationDbHelper(mContext)
        val db = dbHelper.writableDatabase

        val values = ContentValues()
        values.put(NotificationDbHelper.COLUMN_TITLE, notificationData["title"] as? String)
        values.put(NotificationDbHelper.COLUMN_PACKAGE_NAME, notificationData["package_name"] as? String)
        values.put(NotificationDbHelper.COLUMN_TEXT, notificationData["text"] as? String)
        values.put(NotificationDbHelper.COLUMN_BIG_TEXT, notificationData["bigText"] as? String)
        values.put(NotificationDbHelper.COLUMN__ID, notificationData["_id"] as? String)
        values.put(NotificationDbHelper.COLUMN_ID, notificationData["id"] as? String)
        values.put(NotificationDbHelper.COLUMN_CHANNEL_ID, notificationData["channelId"] as? String)
        values.put(NotificationDbHelper.COLUMN_TIMESTAMP, notificationData["timestamp"] as? Long)
        // Add other columns here

        db.insert(NotificationDbHelper.TABLE_NOTIFICATIONS, null, values)
        db.close()
    }

    private fun saveNotificationTmpToLocalDb(notificationData: Map<String, String>) {
        val dbHelper = NotificationTmpDbHelper(mContext)
        val db = dbHelper.writableDatabase

        val values = ContentValues()
        values.put(NotificationTmpDbHelper.COLUMN_TITLE, notificationData["title"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN_PACKAGE_NAME, notificationData["package_name"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN_TEXT, notificationData["text"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN_BIG_TEXT, notificationData["bigText"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN__ID, notificationData["_id"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN_ID, notificationData["id"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN_CHANNEL_ID, notificationData["channelId"] as? String)
        values.put(NotificationTmpDbHelper.COLUMN_TIMESTAMP, notificationData["timestamp"] as? Long)
        // Add other columns here

        db.insert(NotificationTmpDbHelper.TABLE_NOTIFICATIONS, null, values)
        db.close()
    }

    private fun serializeNotification(notificationData: Map<String, String>): String {
        val filteredData = notificationData.filterKeys {
            it == "title" ||
            it == "package_name" ||
            it == "text" ||
            it == "bigText" ||
            it == "id" ||
            it == "_id" ||
            it == "channelId" ||
            it == "timestamp"
        }
        return Gson().toJson(filteredData)
    }

    private fun listNotifications(): List<Map<String, Any>> {
        val dbHelper = NotificationDbHelper(mContext)
        val db = dbHelper.readableDatabase

        val notifications = mutableListOf<Map<String, Any>>()
        val cursor = db.query(
            NotificationDbHelper.TABLE_NOTIFICATIONS,
            null,
            null,
            null,
            null,
            null,
            null
        )

        while (cursor.moveToNext()) {
            val notification = HashMap<String, Any>();
            notification["title"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN_TITLE)) ?: ""
            notification["package_name"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN_PACKAGE_NAME)) ?: ""
            notification["text"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN_TEXT)) ?: ""
            notification["bigText"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN_BIG_TEXT)) ?: ""
            notification["_id"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN__ID)) ?: ""
            notification["id"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN_ID)) ?: ""
            notification["channelId"] = cursor.getString(cursor.getColumnIndex(NotificationDbHelper.COLUMN_CHANNEL_ID)) ?: ""
            notification["timestamp"] = cursor.getLong(cursor.getColumnIndex(NotificationDbHelper.COLUMN_TIMESTAMP))
            // Add other columns here
            notifications.add(notification)
        }

        cursor.close()
        db.close()

        return notifications
    }

    private fun listNotificationsTmp(): List<Map<String, Any>> {
        val dbHelper = NotificationTmpDbHelper(mContext)
        val db = dbHelper.readableDatabase

        val notifications = mutableListOf<Map<String, Any>>()
        val cursor = db.query(
            NotificationTmpDbHelper.TABLE_NOTIFICATIONS,
            null,
            null,
            null,
            null,
            null,
            null
        )

        while (cursor.moveToNext()) {
            val notification = HashMap<String, Any>();
            notification["title"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_TITLE)) ?: ""
            notification["package_name"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_PACKAGE_NAME)) ?: ""
            notification["text"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_TEXT)) ?: ""
            notification["bigText"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_BIG_TEXT)) ?: ""
            notification["_id"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN__ID)) ?: ""
            notification["id"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_ID)) ?: ""
            notification["channelId"] = cursor.getString(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_CHANNEL_ID)) ?: ""
            notification["timestamp"] = cursor.getLong(cursor.getColumnIndex(NotificationTmpDbHelper.COLUMN_TIMESTAMP))
            // Add other columns here
            notifications.add(notification)
        }

        cursor.close()
        db.close()

        return notifications
    }

    private fun getNotificationsFromStatusBar(): List<Map<String, Any?>> {
        val notifications = mutableListOf<Map<String, Any?>>()
        val activeNotifications = activeNotifications
        for (sbn in activeNotifications) {

            val evt = NotificationEvent(mContext, sbn)
            //notifications.add(evt.data)

            var title = evt.data["title"]
            var package_name = evt.data["package_name"]
            var text = evt.data["text"]
            var bigText = evt.data["bigText"]

            var info = Utils.Marshaller.marshal(sbn) as HashMap<*, *>;
//            println("------")
//            println(info)
//            println("---==---")
            var notific = info["notification"] as? Map<*, *>
            var extras = notific?.get("extras") as? Map<*, *>
            var messages = extras?.get("android.messages") as? List<Map<*, *>>
            if(messages == null) {
                val item = HashMap<String, Any?>()
                item["title"] = title
                item["package_name"] = package_name
                item["text"] = text
                item["bigText"] = bigText
                notifications.add(item)
            } else {
                for (message in messages) {
                    var title = message["sender"] as? String
                    if(title == null) {
                        title = ""
                    }
                    var text = message["text"] as? String
                    if(text == null) {
                        text = ""
                    }

                    val item = HashMap<String, Any?>()
                    item["title"] = title
                    item["package_name"] = package_name
                    item["text"] = text
                    item["bigText"] = ""
                    notifications.add(item)
                }
            }
        }

        return notifications
    }

    private fun deserializeNotification(serializedNotification: String): Map<String, String>? {
        val gson = Gson()
        try {
            return gson.fromJson(serializedNotification, object : TypeToken<Map<String, String>>() {}.type)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun removeNotificationFromLocalDb(timestamp: String, text: String): Boolean {
        val dbHelper = NotificationDbHelper(mContext)
        val db = dbHelper.writableDatabase

        val deletedRows = db.delete(NotificationDbHelper.TABLE_NOTIFICATIONS, "${NotificationDbHelper.COLUMN_TIMESTAMP} = ?", arrayOf("$timestamp"))

        db.close()

        return deletedRows > 0
    }

    private fun removeNotificationTmpFromLocalDb(timestamp: String, text: String): Boolean {
        val dbHelper = NotificationTmpDbHelper(mContext)
        val db = dbHelper.writableDatabase

        val deletedRows = db.delete(NotificationTmpDbHelper.TABLE_NOTIFICATIONS, "${NotificationTmpDbHelper.COLUMN_TIMESTAMP} = ?", arrayOf("$timestamp"))

        db.close()

        return deletedRows > 0
    }
}

