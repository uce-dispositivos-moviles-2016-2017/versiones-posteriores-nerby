package com.darwindeveloper.bchat;

import android.app.Activity;
import android.app.ActivityOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.darwindeveloper.bchat.base_datos.ChatContract;
import com.darwindeveloper.bchat.base_datos.ChatDbHelper;
import com.darwindeveloper.bchat.base_datos.ExtrasSQLite;
import com.darwindeveloper.bchat.chat.ChatAdapter;
import com.darwindeveloper.bchat.chat.SMS;
import com.darwindeveloper.bchat.extras.Dispositivo;
import com.darwindeveloper.bchat.extras.SpinnerAdapter;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener, ChatAdapter.OnCheckBoxItemClickListener, ChatAdapter.OnLongItemClickListener {


    public static boolean is_sms_select = false;

    Toolbar toolbar;
    ArrayList<Dispositivo> dispositivos_vinculados = new ArrayList<>();


    //para la coneccion con la base de datos
    private ChatDbHelper mDbHelper;
    private SQLiteDatabase db;
    private ArrayList<SMS> list_sms = new ArrayList<>();
    private ArrayList<SMS> list_sms_select = new ArrayList<>();
    private RecyclerView recyclerViewCHAT;
    private ChatAdapter mChatAdapter;
    LoadChat mLoadChat;


    // tipos de mensajes que se enviara el BluetoothChatService a nuestro Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    public static final int MESSAGE_CONNECTED = 6;


    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String DEVICE_ADRESS = "device_address";
    public static final String TOAST = "toast";


    //
    private static final int REQUEST_ENABLE_BT = 2;//clave para activar el bluetooth con startActivityForResult
    private static final int REQUEST_GET_DEVICE_TO_SEARCH = 3;//para obtener un dispositivo bluedo por medio del metodo startActivityForResult


    private BluetoothAdapter mBluetoothAdapter;//adaptador bluetooth para detectar dispositivos y realizar operaciones bluetooth
    private Spinner spinner_devices;//spinner que mostrara la lista de dispositivos vinculados
    private EditText mOutEditText;//EditText en donde se escribe el mensaje


    private StringBuffer mOutStringBuffer;// String buffer para los mensajes de salida


    // se encarga del servicio de chat
    private BluetoothChatService mChatService = null;
    private String MY_MAC;//almacena my mac address del bluetooth
    private String TO_MAC;//almacena el mac address del bluetooth con el que nos vamos a connectar


    // The Handler que obtinen informacion de vuelta del BluetoothChatService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_WRITE:
                    Toast.makeText(MainActivity.this, "Mensaje Enviado", Toast.LENGTH_SHORT).show();
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);

                    //agregamos el mensaje enviado a la base de datos
                    if (mChatService.getState() == BluetoothChatService.STATE_CONNECTED) {
                        Long last_id = ExtrasSQLite.insertNewSmsChat(db, "YO", MY_MAC, TO_MAC, writeMessage);
                        if (last_id != -1) {
                            list_sms.add(new SMS(last_id, "YO", writeMessage, ExtrasSQLite.getDateTime()));
                            mChatAdapter.notifyItemInserted(list_sms.size() - 1);
                            mChatAdapter.notifyDataSetChanged();
                            recyclerViewCHAT.scrollToPosition(list_sms.size() - 1);
                        }
                    }


                    break;
                case MESSAGE_READ:
                    Toast.makeText(MainActivity.this, "Tienes un nuevo Mensaje", Toast.LENGTH_SHORT).show();
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    // Vibrate for 500 milliseconds
                    v.vibrate(100);

                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);

                    //agregamos el mensaje recivido a la base de datos
                    if (mChatService.getState() == BluetoothChatService.STATE_CONNECTED) {
                        Long last_ide = ExtrasSQLite.insertNewSmsChat(db, "EL", MY_MAC, TO_MAC, readMessage);
                        if (last_ide != -1) {
                            list_sms.add(new SMS(last_ide, "EL", readMessage, ExtrasSQLite.getDateTime()));
                            mChatAdapter.notifyItemInserted(list_sms.size() - 1);
                            mChatAdapter.notifyDataSetChanged();
                            recyclerViewCHAT.scrollToPosition(list_sms.size() - 1);
                        }
                    }


                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    TO_MAC = msg.getData().getString(DEVICE_ADRESS);
                    mostrarSnackBar("CONECTADO A " + msg.getData().getString(DEVICE_NAME), android.R.color.holo_green_dark);
                    spinner_devices.setSelection(buscarDeviceEnSpinner(TO_MAC), true);
                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //preparamos la base de datos
        mDbHelper = new ChatDbHelper(MainActivity.this);
        // Gets the data repository in write mode
        db = mDbHelper.getWritableDatabase();
        mLoadChat = new LoadChat();


        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);


        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();//inicializamos el adaptador Bluetooth


        spinner_devices = (Spinner) findViewById(R.id.spinner_devices);
        mOutEditText = (EditText) findViewById(R.id.text_sms);


        recyclerViewCHAT = (RecyclerView) findViewById(R.id.recyclerview_chat);
        // RecyclerView.LayoutManager mLayoutManager = new GridLayoutManager(context, 2);
        recyclerViewCHAT.setLayoutManager(new LinearLayoutManager(MainActivity.this));
        //recyclerView.addItemDecoration(new GridSpacingItemDecoration(2, dpToPx(10), true));
        recyclerViewCHAT.setItemAnimator(new DefaultItemAnimator());
        mChatAdapter = new ChatAdapter(MainActivity.this, list_sms);
        mChatAdapter.setOnCheckBoxItemClickListener(this);
        mChatAdapter.setOnLongItemClickListener(this);
        recyclerViewCHAT.setAdapter(mChatAdapter);


        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no esta disponible", Toast.LENGTH_LONG).show();
            finish();
            return;
        }


        MY_MAC = mBluetoothAdapter.getAddress();


        //PREPARAMOS EL SPINNER
        setUpSpinnerDevicesBluetooth();
    }


    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    setUpSpinnerDevicesBluetooth();
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occured
                    Toast.makeText(this, "Para usar esta aplicacion debes activar el Bluetooth ", Toast.LENGTH_SHORT).show();
                    finish();
                }

                break;

            case REQUEST_GET_DEVICE_TO_SEARCH:
                if (resultCode == Activity.RESULT_OK) {

                    TO_MAC = data.getStringExtra(SearchDevicesActivity.SEARCH_DEVICE_MAC);
                    //buscamos si el dispositivo obtenido del dialogActivity ya estaba en el spiner de dispositivos vinculados
                    if (buscarDeviceEnSpinner(TO_MAC) != -1) {
                        Toast.makeText(MainActivity.this, "El dispositivo seleccionado ya esta en la lista principal", Toast.LENGTH_LONG).show();
                    } else {
                        String TO_DEVICE_NAME = data.getStringExtra(SearchDevicesActivity.SEARCH_DEVICE_NAME);

                        //PREPARAMOS EL SPINNER
                        dispositivos_vinculados = getDevices();
                        dispositivos_vinculados.add(new Dispositivo(MY_MAC, TO_DEVICE_NAME));
                        SpinnerAdapter spinnerAdapter = new SpinnerAdapter(MainActivity.this, R.layout.item_sppiner_device, dispositivos_vinculados);
                        spinner_devices.setAdapter(spinnerAdapter);
                        spinner_devices.setOnItemSelectedListener(this);
                        spinner_devices.setSelection(dispositivos_vinculados.size() - 1);
                        //carcamo el chat
                        mChatService.connect(mBluetoothAdapter.getRemoteDevice(TO_MAC));
                        if (mLoadChat.getStatus() == AsyncTask.Status.RUNNING || mLoadChat.getStatus() == AsyncTask.Status.FINISHED) {
                            mLoadChat = null;
                        }
                        mLoadChat = new LoadChat();
                        mLoadChat.execute();
                    }
                }
                break;
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            setUpSpinnerDevicesBluetooth();
        }

        if (id == R.id.action_search) {
            Intent intent = new Intent(MainActivity.this, SearchDevicesActivity.class);

            //una pequeña animacion para cuando se lance SearchDevicesActivity
            Bundle bndlanimation =
                    ActivityOptions.makeCustomAnimation(getApplicationContext(), R.anim.left_animation, R.anim.right_animation).toBundle();

            startActivityForResult(intent, REQUEST_GET_DEVICE_TO_SEARCH, bndlanimation);
        }

        if (id == R.id.action_view) {
            //hacemos visible el dispositivo bluetooth para que otros lo encuentren
            Intent discoverableIntent = new
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 600);
            startActivity(discoverableIntent);
        }


        if (id == R.id.action_info) {
            Intent intent = new Intent(MainActivity.this, InformacionActivity.class);

            //una pequeña animacion para cuando se lance SearchDevicesActivity
            Bundle bndlanimation =
                    ActivityOptions.makeCustomAnimation(getApplicationContext(), R.anim.left_animation, R.anim.right_animation).toBundle();

            startActivity(intent, bndlanimation);
        }


        if (id == R.id.action_ajustes) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);

            //una pequeña animacion para cuando se lance SearchDevicesActivity
            Bundle bndlanimation =
                    ActivityOptions.makeCustomAnimation(getApplicationContext(), R.anim.left_animation, R.anim.right_animation).toBundle();

            startActivity(intent, bndlanimation);
        }


        if (id == R.id.action_help) {
            Intent intent = new Intent(MainActivity.this, ManualDeUsuarioActivity.class);

            //una pequeña animacion para cuando se lance SearchDevicesActivity
            Bundle bndlanimation =
                    ActivityOptions.makeCustomAnimation(getApplicationContext(), R.anim.left_animation, R.anim.right_animation).toBundle();
            startActivity(intent, bndlanimation);
        }


        if (id == android.R.id.home) {
            //escondemos el menu que se mostro cunado se presiono largamente un sms en el chat
            is_sms_select = false;
            mChatAdapter.notifyDataSetChanged();//notificamos los cambios en el recyclerview

            //cambiamos al toolbar normal
            toolbar.getMenu().clear();
            toolbar.inflateMenu(R.menu.menu_main);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            spinner_devices.setVisibility(View.VISIBLE);
        }


        if (id == R.id.action_delete) {
            new DeleteSms().execute();
        }

        return true;
    }


    public void onItemSelected(AdapterView<?> parent, View view,
                               int pos, long id) {
        // An item was selected. You can retrieve the selected item using
        // parent.getItemAtPosition(pos)

        if (parent.getId() == spinner_devices.getId()) {
            if (pos != 0) {//si hay un dispositivo bluetooth seleccionado en el spinner
                if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
                    TO_MAC = dispositivos_vinculados.get(pos).getMAC();//recuperamos la direccion mac del otro dispositivo bluetooth
                    mChatService.connect(mBluetoothAdapter.getRemoteDevice(TO_MAC));
                    if (mLoadChat.getStatus() == AsyncTask.Status.RUNNING || mLoadChat.getStatus() == AsyncTask.Status.FINISHED) {
                        mLoadChat = null;
                    }
                    mLoadChat = new LoadChat();
                    mLoadChat.execute();
                } else {
                    spinner_devices.setSelection(buscarDeviceEnSpinner(BluetoothChatService.ADDRESS_CONNECTED), true);
                }
            } else {
                TO_MAC = null;
                // mChatService.stop();
            }

        }
    }

    public void onNothingSelected(AdapterView<?> parent) {
        // Another interface callback
    }


    //MIS  METODOS

    private void setUpSpinnerDevicesBluetooth() {
        //PREPARAMOS EL SPINNER
        dispositivos_vinculados = getDevices();
        SpinnerAdapter spinnerAdapter = new SpinnerAdapter(MainActivity.this, R.layout.item_sppiner_device, dispositivos_vinculados);
        spinner_devices.setAdapter(spinnerAdapter);
        spinner_devices.setOnItemSelectedListener(this);

    }


    private void mostrarSnackBar(String sms, int color) {
        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), sms, Snackbar.LENGTH_SHORT);
        snackbar.setAction("Action", null).show();
        snackbar.getView().setBackgroundColor(getResources().getColor(color));
        snackbar.show();
    }


    private int buscarSMS(ArrayList<SMS> smss, long ID) {
        int pos = -1;

        for (int i = 0; i < smss.size(); i++) {
            if (smss.get(i).getID() == ID) {
                pos = i;
                break;
            }
        }
        return pos;
    }

    private int buscarDeviceEnSpinner(String adress) {
        int pos = -1;

        for (int i = 0; i < dispositivos_vinculados.size(); i++) {
            if (dispositivos_vinculados.get(i).getMAC().equals(adress)) {
                pos = i;
                break;
            }
        }
        return pos;
    }


    /**
     * @return lista de dispositivos vinculados al telefono
     */
    private ArrayList<Dispositivo> getDevices() {


        ArrayList<Dispositivo> dispositivos_vinculados = new ArrayList<>();

        dispositivos_vinculados.add(new Dispositivo("un dispositivo", "Seleccione"));


        //una coleccion de todos loas dispositivos vinculados al telefono android
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        // si hay dispositivos vinculados
        if (pairedDevices.size() > 0) {
            // Loop through paired devices
            for (BluetoothDevice device : pairedDevices) {
                // Add the name and address to an array adapter to show in a ListView
                Dispositivo tmp = new Dispositivo(device.getAddress(), device.getName());
                dispositivos_vinculados.add(tmp);
            }
        }

        return dispositivos_vinculados;
    }


    private void setupChat() {
        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new BluetoothChatService(this, mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }


    public void sendMessage(View v) {

        String message = mOutEditText.getText().toString();//obtenemos el texto escrito en el editText del mensaje

        // Check that we're actually connected before trying anything
        if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(this, "NO estas conectado con un dispositivo", Toast.LENGTH_SHORT).show();
            return;
        }
        // comprobamos que no enviemos un mensaje vacio
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = message.getBytes();
            mChatService.write(send);
            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer.setLength(0);
            mOutEditText.setText(mOutStringBuffer);

        }
    }


    //CICLO DE VIDA DE LA ACTIVIDAD
    @Override
    public void onStart() {
        super.onStart();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        } else {
            if (mChatService == null) {
                setupChat();
            }
        }
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        if (mChatService != null) {
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                mChatService.start();
            }
        }
    }


    @Override
    public synchronized void onPause() {
        super.onPause();

    }

    @Override
    public void onStop() {
        super.onStop();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mChatService != null) mChatService.stop();
        is_sms_select = false;
    }


    @Override
    public void onCheckBoxItemClick(CheckBox checkbok, SMS sms, int position) {

        if (checkbok.isChecked()) {
            list_sms_select.add(sms);

        } else {
            //buscamos el sms en el arraylist y luego lo removemos
            int pos = buscarSMS(list_sms_select, sms.getID());
            if (pos != -1) {
                list_sms_select.remove(pos);
            }
        }

        //mChatAdapter.notifyDataSetChanged();

        getSupportActionBar().setTitle("selecionados " + list_sms_select.size());

    }

    @Override
    public void onLongSmsItemClick(SMS sms, int position) {
        //escondemos el spinner
        spinner_devices.setVisibility(View.GONE);
        //tenemos un sms del chat seleccionado
        is_sms_select = true;
        mChatAdapter.notifyDataSetChanged();//notificamos los cambios en el recyclerview

        //cambiamos del toolbar normal al toolbar sms chat
        toolbar.getMenu().clear();
        toolbar.inflateMenu(R.menu.menu_sms_chat);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(true);
    }


    //MIS CLASES INTERNAS
    private class LoadChat extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {
            //limpiamos el chat con la antigua conversacion
            list_sms.clear();
            //eliminanmos los mensajes viejos del chat para remplazarlos con los nuevos
            mChatAdapter.notifyItemRangeRemoved(0, mChatAdapter.getItemCount() - 1);
            mChatAdapter.notifyDataSetChanged();
        }

        @Override
        protected Void doInBackground(Void... voids) {

            Cursor cursor = ExtrasSQLite.getChat(db, MY_MAC, TO_MAC);
            //recuperamos los mensajes del chat y los almacenamos en un array list
            if (cursor.moveToFirst()) {
                String[] columnNames = cursor.getColumnNames();
                do {

                    //variables para crear un objetode la clase SMS e agregarlos a la lista de mensajes
                    String yo = null, el = null, sms = null, date_time = null, from = null;
                    long id = -1;

                    for (String columnName : columnNames) {
                        String column = cursor.getString(cursor.getColumnIndex(columnName));


                        if (columnName.equals(ChatContract.ChatsEntry._ID)) {
                            id = Long.parseLong(column);
                        }

                        if (columnName.equals(ChatContract.ChatsEntry.YO)) {
                            yo = column;
                        }

                        if (columnName.equals(ChatContract.ChatsEntry.EL)) {
                            el = column;
                        }


                        if (columnName.equals(ChatContract.ChatsEntry.FROM)) {
                            from = column;
                        }

                        if (columnName.equals(ChatContract.ChatsEntry.SMS)) {
                            sms = column;
                        }

                        if (columnName.equals(ChatContract.ChatsEntry.DATE_TIME)) {
                            date_time = column;
                        }

                    }

                    //agregamos el mensaje al chat
                    list_sms.add(new SMS(id, from, sms, date_time));


                } while (cursor.moveToNext());
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            //notificamos los cambios al adapter
            mChatAdapter.notifyItemRangeInserted(0, list_sms.size() - 1);
            mChatAdapter.notifyDataSetChanged();
            recyclerViewCHAT.scrollToPosition(list_sms.size() - 1);
        }
    }


    private class DeleteSms extends AsyncTask<Void, Void, Void> {

        private ArrayList<Integer> tmp_posiciones = new ArrayList<>();

        @Override
        protected Void doInBackground(Void... voids) {

            for (SMS sms : list_sms_select) {
                //eliminamos el mensaje de la base de datos
                boolean result = ExtrasSQLite.delete_sms(db, Long.toString(sms.getID()));
                if (result) {
                    //eliminamos el mensaje del chat
                    int pos = buscarSMS(list_sms, sms.getID());

                    //removemos el mensaje de la lista del chat
                    if (pos != -1) {
                        list_sms.remove(pos);
                        tmp_posiciones.add(pos);
                    }


                }
            }
            return null;
        }


        @Override
        protected void onPostExecute(Void aVoid) {

            //actualizamos el recyclerview borrando todos los mensajes eliminados de la base de datos
            list_sms_select.clear();

            for (int i = 0; i < tmp_posiciones.size(); i++) {
                mChatAdapter.notifyItemRemoved(tmp_posiciones.get(i));
            }
            mChatAdapter.notifyDataSetChanged();
            try {
                getSupportActionBar().setTitle("NO S.");
            } catch (Exception e) {
                Log.e("Error delete sms", e.getMessage());
            }

        }

        private void notifyChatAdater(int position) {


        }
    }


}
