package io.cordova.hellocordova;

import android.content.Intent;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;

public class NoseDetectionPlugin extends CordovaPlugin {

    @Override
    public boolean execute(String action, JSONArray data,
                           CallbackContext callbackContext) throws JSONException {
        if (action.equals("startDetection")) {
            Intent intent = new Intent(this.cordova.getActivity(), DetectorActivity.class);
            this.cordova.startActivityForResult(this, intent, 0);
            return true;
        }
        return false;
    }
}
