package eu.faircode.netguard;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Xml;
import android.widget.Toast;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

public class ActivitySettings extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "NetGuard.Settings";

    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final Intent INTENT_VPN_SETTINGS = new Intent("android.net.vpn.SETTINGS");

    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        setTheme(prefs.getBoolean("dark_theme", false) ? R.style.AppThemeDark : R.style.AppTheme);

        super.onCreate(savedInstanceState);

        getFragmentManager().beginTransaction().replace(android.R.id.content, new FragmentSettings()).commit();
    }

    @Override
    public void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    public void setup(PreferenceScreen screen) {
        Preference pref_export = screen.findPreference("export");
        pref_export.setEnabled(getIntentCreateDocument().resolveActivity(getPackageManager()) != null);
        pref_export.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivityForResult(getIntentCreateDocument(), ActivitySettings.REQUEST_EXPORT);
                return true;
            }
        });

        Preference pref_import = screen.findPreference("import");
        pref_import.setEnabled(getIntentCreateDocument().resolveActivity(getPackageManager()) != null);
        pref_import.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivityForResult(getIntentOpenDocument(), ActivitySettings.REQUEST_IMPORT);
                return true;
            }
        });

        Preference pref_vpn = screen.findPreference("vpn");
        pref_vpn.setEnabled(INTENT_VPN_SETTINGS.resolveActivity(this.getPackageManager()) != null);
        pref_vpn.setIntent(INTENT_VPN_SETTINGS);

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        if ("whitelist_wifi".equals(name))
            SinkholeService.reload("wifi", this);

        else if ("whitelist_other".equals(name))
            SinkholeService.reload("other", this);

        else if ("whitelist_roaming".equals(name))
            SinkholeService.reload("other", this);

        else if ("manage_system".equals(name))
            SinkholeService.reload(null, this);

        else if ("dark_theme".equals(name))
            recreate();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleExport(data);

        } else if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleImport(data);

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void handleExport(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                OutputStream out = null;
                try {
                    out = getContentResolver().openOutputStream(data.getData());
                    Log.i(TAG, "Writing URI=" + data.getData());
                    xmlExport(out);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (out != null)
                        try {
                            out.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (ex == null)
                    Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
        }.execute();
    }

    private static Intent getIntentCreateDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/xml");
        intent.putExtra(Intent.EXTRA_TITLE, "netguard.xml");
        return intent;
    }

    private static Intent getIntentOpenDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/xml");
        return intent;
    }

    private void handleImport(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(data.getData());
                    Log.i(TAG, "Reading URI=" + data.getData());
                    xmlImport(in);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (in != null)
                        try {
                            in.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (ex == null) {
                    SinkholeService.reload(null, ActivitySettings.this);
                    recreate();
                    Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                } else
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
        }.execute();
    }

    private void xmlExport(OutputStream out) throws IOException {
        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(out, "UTF-8");
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "netguard");

        serializer.startTag(null, "application");
        xmlExport(PreferenceManager.getDefaultSharedPreferences(this), serializer);
        serializer.endTag(null, "application");

        serializer.startTag(null, "wifi");
        xmlExport(getSharedPreferences("wifi", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "wifi");

        serializer.startTag(null, "mobile");
        xmlExport(getSharedPreferences("other", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "mobile");

        serializer.startTag(null, "unused");
        xmlExport(getSharedPreferences("unused", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "unused");

        serializer.endTag(null, "netguard");
        serializer.endDocument();
        serializer.flush();
    }

    private void xmlExport(SharedPreferences prefs, XmlSerializer serializer) throws IOException {
        Map<String, ?> settings = prefs.getAll();
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (value instanceof Boolean) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "boolean");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");
            } else
                Log.e(TAG, "Unknown key=" + key);
        }
    }

    private void xmlImport(InputStream in) throws IOException, SAXException, ParserConfigurationException {
        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        XmlImportHandler handler = new XmlImportHandler();
        reader.setContentHandler(handler);
        reader.parse(new InputSource(in));

        xmlImport(handler.application, PreferenceManager.getDefaultSharedPreferences(this));
        xmlImport(handler.wifi, getSharedPreferences("wifi", Context.MODE_PRIVATE));
        xmlImport(handler.mobile, getSharedPreferences("other", Context.MODE_PRIVATE));
        xmlImport(handler.unused, getSharedPreferences("unused", Context.MODE_PRIVATE));
    }

    private void xmlImport(Map<String, Object> settings, SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();

        for (String key : prefs.getAll().keySet())
            editor.remove(key);

        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else
                Log.e(TAG, "Unknown type=" + value.getClass());
        }

        editor.apply();
    }

    private class XmlImportHandler extends DefaultHandler {
        public Map<String, Object> application = new HashMap<>();
        public Map<String, Object> wifi = new HashMap<>();
        public Map<String, Object> mobile = new HashMap<>();
        public Map<String, Object> unused = new HashMap<>();
        private Map<String, Object> current = null;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (qName.equals("netguard"))
                ; // Ignore

            else if (qName.equals("application"))
                current = application;

            else if (qName.equals("wifi"))
                current = wifi;

            else if (qName.equals("mobile"))
                current = mobile;

            else if (qName.equals("unused"))
                current = unused;

            else if (qName.equals("setting")) {
                String key = attributes.getValue("key");
                String type = attributes.getValue("type");
                String value = attributes.getValue("value");

                if (current == null)
                    Log.e(TAG, "No current key=" + key);
                else {
                    if ("boolean".equals(type))
                        current.put(key, Boolean.parseBoolean(value));
                    else
                        Log.e(TAG, "Unknown type key=" + key);
                }

            } else
                Log.e(TAG, "Unknown element qname=" + qName);
        }
    }
}
