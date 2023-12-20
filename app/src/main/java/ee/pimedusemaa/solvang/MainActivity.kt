package ee.pimedusemaa.solvang


import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import ee.pimedusemaa.solvang.MainActivity.RemindersManager.sendReminderNotification
import ee.pimedusemaa.solvang.ui.theme.SolvangTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

val poppins = FontFamily(
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_regular, FontWeight.Normal)
)

private const val BASE_URL = "https://kotikone.xyz/solvang"

private const val PREFS_FILENAME = "SolvangPrefs"
private const val STRING_KEY = "solvangPrefTime"


private const val CHANNEL_ID = "ee.pimedusemaa.solvang.insult"
private const val notificationId = 0

private var notificationPerm = false

var globalSolvangTime = "10:00"

@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pushNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        super.onCreate(savedInstanceState)
        if (PrefManager.loadString(this) != ""){
            globalSolvangTime = PrefManager.loadString(this)
        }
        RemindersManager.startReminder(this)
        setContent {
            MaterialTheme {
                SolvangApp(this)
            }
        }
        createNotificationChannel()
    }

    object PrefManager {
        fun saveString(context: Context, value: String){
            val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
            val editor: SharedPreferences.Editor = sharedPreferences.edit()
            editor.putString(STRING_KEY, value)
            editor.apply()
        }

        fun loadString(context: Context): String{
            val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
            return sharedPreferences.getString(STRING_KEY, "") ?: ""
        }
    }

    private val pushNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->    //Used to be 'Granted' instead of '_', changed to reduce compiler overhead or whatever due to being unused
        notificationPerm = true
    }

   object RemindersManager {
       private const val REMINDER_NOTIFICATION_REQUEST_CODE = 123
       fun startReminder(
           context: Context,
           reminderTime: String = globalSolvangTime,
           reminderId: Int = REMINDER_NOTIFICATION_REQUEST_CODE
       ){
           println(reminderTime)
           val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
           val (hours, min) = reminderTime.split(":").map {it.toInt()}
           val intent =
               Intent(context.applicationContext, AlarmReceiver::class.java).let { intent ->
                   PendingIntent.getBroadcast(
                       context.applicationContext,
                       reminderId,
                       intent,
                       PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                   )
               }
           val calendar: Calendar = Calendar.getInstance(Locale.getDefault()).apply {
               set(Calendar.HOUR_OF_DAY, hours)
               set(Calendar.MINUTE, min)
           }
           if (Calendar.getInstance(Locale.getDefault()).apply { add(Calendar.MINUTE, 1) }.timeInMillis - calendar.timeInMillis > 0){
               calendar.add(Calendar.DATE, 1)
           }
           alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(calendar.timeInMillis, intent), intent)

       }
       fun stopReminder(
           context: Context,
           reminderId: Int
       ){
           val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
           val intent = Intent(context, AlarmReceiver::class.java).let { intent ->
               PendingIntent.getBroadcast(
                   context,
                   reminderId,
                   intent,
                   PendingIntent.FLAG_IMMUTABLE //FIXME USED TO BE 0
               )
           }
           alarmManager.cancel(intent)
           println("yeh what the fuck this is so retarded")
       }
       class AlarmReceiver : BroadcastReceiver() {

           /**
            * sends notification when receives alarm
            * and then reschedule the reminder again
            * */
           override fun onReceive(context: Context, intent: Intent) {
//               val notificationManager = ContextCompat.getSystemService(
//                   context,
//                   NotificationManager::class.java
//               ) as NotificationManager
//
//               notificationManager.sendReminderNotification(
//                   applicationContext = context,
//                   channelId = context.getString(R.string.channel_id),
//                   "hardcoded"
//               )
               stringRequest(context)



               // Remove this line if you don't want to reschedule the reminder
               startReminder(context.applicationContext, PrefManager.loadString(context))
           }
       }

       class BootReceiver : BroadcastReceiver(){
           override fun onReceive(context: Context, intent: Intent) {
               if (intent.action == "android.intent.action.BOOT_COMPLETED"){
                   println("booted")
                   startReminder(context)
               }
           }
       }

       fun NotificationManager.sendReminderNotification(
           applicationContext: Context,
           channelId: String,
           insult: String
       ) {
           val contentIntent = Intent(applicationContext, MainActivity::class.java)
           val pendingIntent = PendingIntent.getActivity(
               applicationContext,
               1,
               contentIntent,
               PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
           )
           val builder = NotificationCompat.Builder(applicationContext, channelId)
               .setContentTitle("The insult of the day is:")
               .setContentText(insult)
               .setSmallIcon(R.drawable.dice_1)
               .setStyle(
                   NotificationCompat.BigTextStyle()
                       .bigText(insult)
               )
               .setContentIntent(pendingIntent)
               .setAutoCancel(true)

           notify(NOTIFICATION_ID, builder.build())
       }

       private const val NOTIFICATION_ID = 1
   }


    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val name = "Solvang Insult"
            val descriptionText = "Insults in estonian"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(){
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()){
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Näksi peeeru")
                .setContentText("Situ pihkuu")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            with(NotificationManagerCompat.from(this)){
                notify(notificationId, builder.build())
            }
        }
    }
}


@ExperimentalMaterial3Api
@Composable
fun SolvangApp(context: Context){
    MainContent(context, modifier = Modifier
        .fillMaxSize()
        .wrapContentSize(Alignment.Center))
}


@ExperimentalMaterial3Api
@Composable
fun MainContent(context: Context, modifier: Modifier = Modifier) {
    SolvangTheme {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val solvangtime = remember { mutableStateOf(globalSolvangTime) }
        var showTimePicker by remember { mutableStateOf(false) }
        var showChangeApi by remember { mutableStateOf(false) }
        val state = rememberTimePickerState()
        val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        val snackState = remember { SnackbarHostState() }
        val snackScope = rememberCoroutineScope()
        SnackbarHost(hostState = snackState)

        if (showChangeApi) {
            changeApiDialog(
                onDismissRequest = { showChangeApi = false },
                onConfirmation = { showChangeApi = false },
                dialogTitle = "Set the API url",
                icon = Icons.Default.Info
            )
        }

        if (showTimePicker) {
            TimePickerDialog(onCancel = { showTimePicker = false }, onConfirm = {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, state.hour)
                cal.set(Calendar.MINUTE, state.minute)
                cal.isLenient = false
                solvangtime.value = formatter.format(cal.time)
                MainActivity.PrefManager.saveString(context, formatter.format(cal.time))
                globalSolvangTime = formatter.format(cal.time)
                MainActivity.RemindersManager.stopReminder(context, 123)
                MainActivity.RemindersManager.startReminder(context)
                snackScope.launch {
                    snackState.showSnackbar("Entered time: ${formatter.format(cal.time)}")
                }
                showTimePicker = false
            }) {
                Surface {
                    TimePicker(state = state)
                }
            }
        }
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SOLVANG",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = poppins
                )
            }
            Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "You will receive an insult daily at", fontFamily = poppins, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20), modifier = Modifier.width(200.dp), onClick = {showTimePicker = true}){
                    Text(text = solvangtime.value, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(15.dp), textAlign = TextAlign.Center)
                }
                Spacer(modifier = Modifier.height(16.dp))
                //Text(text = "Change the time", fontFamily = poppins, fontSize = 20.sp)
                //Image(painter = painterResource(id = imageResource), contentDescription = result.toString())
                //Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showTimePicker = true }, modifier = Modifier.width(180.dp)) {
                    Text(text = "Change time", fontSize = 18.sp)
                }
                Button(onClick = { showChangeApi = true }, modifier = Modifier.align(Alignment.Start)) {
                    Text(text = "API")
                }
            }
        }
    }
}

@Composable
fun TimePickerDialog(
    title: String = "Select Time",
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    toggle: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .height(IntrinsicSize.Min)
                .background(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface
                ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    text = title,
                    style = MaterialTheme.typography.labelMedium
                )
                content()
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .fillMaxWidth()
                ) {
                    toggle()
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onCancel
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = onConfirm
                    ) { Text("OK") }
                }
            }
        }
    }
}

@Suppress("UnnecessaryOptInAnnotation")
@OptIn(ExperimentalMaterial3Api::class)
fun stringRequest(context: Context){
    val queue = Volley.newRequestQueue(context)
    val stringRequest = StringRequest(Request.Method.GET, BASE_URL,
        {response ->
            println("Response is response")
            val notificationManager = ContextCompat.getSystemService(
                context,
                NotificationManager::class.java
            ) as NotificationManager

            notificationManager.sendReminderNotification(
                applicationContext = context,
                channelId = context.getString(R.string.channel_id),
                response
            )
        }, { println("HTTP error idfk I hate Java") })
    queue.add(stringRequest)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable

fun changeApiDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    dialogTitle: String,
    icon: ImageVector,
) {
    AlertDialog(
        icon = {
            Icon(icon, contentDescription = "Example Icon")
        },
        title = {
            Text(text = dialogTitle)
        },
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmation()
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                }
            ) {
                Text("Dismiss")
            }
        }
    )
}




