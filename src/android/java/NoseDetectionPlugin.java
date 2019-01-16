package com.allpet.nosedetection;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;

public class NoseDetectionPlugin extends CordovaPlugin {

    private CallbackContext      cb;

    @Override
    public boolean execute(String action, JSONArray data,
                           CallbackContext callbackContext) throws JSONException {
        if (action.equals("startDetection")) {
            Intent intent = new Intent(this.cordova.getActivity(), DetectorActivity.class);
            cb = callbackContext;
            this.cordova.startActivityForResult(this, intent, 0);
            return true;
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent){
        switch (resultCode) {
            case Activity.RESULT_OK:
                Bundle b=intent.getExtras();
                byte[] resultImg=b.getByteArray("resultImg");
                cb.success(resultImg);
                break;
            default:
                cb.error("failed");
                break;
        }
    }
}
