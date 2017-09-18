package ca.ubc.cs.cpsc210.mindthegap;

/*
 * Copyright 2016-2017 Department of Computer Science UBC
 */

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;
import ca.ubc.cs.cpsc210.mindthegap.TfL.DataProvider;
import ca.ubc.cs.cpsc210.mindthegap.TfL.TfLHttpArrivalDataProvider;
import ca.ubc.cs.cpsc210.mindthegap.model.Station;
import ca.ubc.cs.cpsc210.mindthegap.model.StationManager;
import ca.ubc.cs.cpsc210.mindthegap.model.exception.StationException;
import ca.ubc.cs.cpsc210.mindthegap.parsers.TfLArrivalsParser;
import ca.ubc.cs.cpsc210.mindthegap.parsers.exception.TfLArrivalsDataMissingException;
import ca.ubc.cs.cpsc210.mindthegap.ui.LocationListener;
import ca.ubc.cs.cpsc210.mindthegap.ui.MapDisplayFragment;
import ca.ubc.cs.cpsc210.mindthegap.ui.StationSelectionListener;

import javax.json.JsonException;

/**
 * Main activity
 */
public class MindTheGap extends Activity implements LocationListener, StationSelectionListener {
    private static final String TSA_TAG = "TSA_TAG";
    private static final String MAP_TAG = "Map Fragment Tag";
    private MapDisplayFragment fragment;
    private TextView nearestStnLabel;
    private Station myNearestStn;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TSA_TAG, "onCreate");

        setContentView(R.layout.map_layout);
        myNearestStn = null;

        if(savedInstanceState != null) {
            Log.i(TSA_TAG, "restoring from instance state");
            fragment = (MapDisplayFragment) getFragmentManager()
                    .findFragmentByTag(MAP_TAG);
            StationManager stnManager = StationManager.getInstance();
            myNearestStn = stnManager.getStationWithId(savedInstanceState.getString("nearestStn"));
        }
        else if(fragment == null) {
            Log.i(TSA_TAG, "fragment was null");

            fragment = new MapDisplayFragment();
            getFragmentManager().beginTransaction()
                    .add(R.id.map_fragment, fragment, MAP_TAG).commit();
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setHomeButtonEnabled(false);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Log.i(TSA_TAG, "onSaveInstanceState");

        if (myNearestStn != null) {
            outState.putString("nearestStn", myNearestStn.getID());
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        nearestStnLabel = (TextView) findViewById(R.id.nearestStnLabel);
        if (myNearestStn == null) {
            nearestStnLabel.setText(R.string.out_of_range);
        }
        else {
            nearestStnLabel.setText(myNearestStn.getName());
        }
    }

    /**
     * Update nearest station text view when user location changes
     *
     * @param nearest  station that is nearest to user (null if no station within StationManager.RADIUS metres)
     */
    @Override
    public void onLocationChanged(Station nearest) {
        if(nearest == null) {
            nearestStnLabel.setText(R.string.out_of_range);
        }
        else {
            myNearestStn = nearest;
            nearestStnLabel.setText(nearest.getName());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.about:
                handleAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Show about dialog to user
     */
    private void handleAbout() {
        Log.d(TSA_TAG, "showing about dialog");
        AlertDialog.Builder dialogBldr = new AlertDialog.Builder(this);
        dialogBldr.setTitle(R.string.about);
        dialogBldr.setView(getLayoutInflater().inflate(R.layout.about_dialog_layout, null));
        dialogBldr.setNeutralButton(R.string.ok, null);
        dialogBldr.create().show();
    }

    /**
     * Download arrivals data for station selected by user;
     * set selected station in StationManager.
     *
     * @param stn   station selected by user
     */
    @Override
    public void onStationSelected(Station stn) {
        try {
            StationManager.getInstance().setSelected(stn);

            ConnectivityManager connMgr = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
            if (networkInfo != null && networkInfo.isConnected()) {
                new DownloadArrivalDataFromTfLTask().execute(stn);
            }
            else {
                Toast.makeText(this, "Unable to establish network connection!", Toast.LENGTH_LONG).show();
            }
        } catch (StationException e) {
            Toast.makeText(this, "Station not found on managed lines", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Start activity to show arrival boards to user
     *
     * @param stn  station for which arrival boards are to be shown
     */
    private void startArrivalBoardActivity(Station stn) {
        Intent i = new Intent(MindTheGap.this, ArrivalBoardActivity.class);
        i.putExtra(getString(R.string.stn_name_key), stn.getName());
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_from_right, android.R.anim.fade_out);
    }

    /**
     * Task that will download and parse arrivals data from TfL
     */
    private class DownloadArrivalDataFromTfLTask extends AsyncTask<Station, Integer, String> {
        private ProgressDialog progressDialog;
        private Station stn;

        @Override
        protected void onPreExecute() {
            progressDialog = ProgressDialog.show(MindTheGap.this, getString(R.string.arrivals_download_title),
                    getString(R.string.arrivals_download_msg), true, false);
        }

        @Override
        protected String doInBackground(Station... stns) {
            stn = stns[0];
            DataProvider dataProvider = new TfLHttpArrivalDataProvider(stn);
            String response = null;

            try {
                response = dataProvider.dataSourceToString();
            } catch (Exception e) {
                Log.d(MindTheGap.TSA_TAG, e.getMessage(), e);
            }

            return response;
        }

        @Override
        protected void onPostExecute(String response) {
            if (response != null) {
                try {
                    stn.clearArrivalBoards();
                    (new TfLArrivalsParser()).parseArrivals(stn, response);
                    startArrivalBoardActivity(stn);
                }
                catch (JsonException e) {
                    Log.d(MindTheGap.TSA_TAG, e.getMessage(), e);
                    Toast.makeText(getApplicationContext(), R.string.tfl_api_json, Toast.LENGTH_LONG).show();
                }
                catch (TfLArrivalsDataMissingException e) {
                    Log.d(MindTheGap.TSA_TAG, e.getMessage(), e);
                    Toast.makeText(getApplicationContext(), R.string.tfl_api_json_missing, Toast.LENGTH_LONG).show();
                }
            }
            else {
                Toast.makeText(getApplicationContext(), R.string.tfl_api_network, Toast.LENGTH_LONG).show();
            }

            progressDialog.dismiss();
        }
    }
}
