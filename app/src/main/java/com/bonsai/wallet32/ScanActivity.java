package com.bonsai.wallet32;
/**
 * Created by Marco Polci on 24/05/2015.
 */

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;

import eu.livotov.zxscan.ScannerView;


public class ScanActivity extends ActionBarActivity implements ScannerView.ScannerViewEventListener {
    public final static String RESULT_EXTRA_STR = "data";
    private ScannerView embeddedScanner;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        embeddedScanner = (ScannerView) findViewById(R.id.scanner);
    }

    /**
     * Dispatch onStart() to all fragments.  Ensure any created loaders are
     * now started.
     */
    @Override
    protected void onStart() {
        super.onStart();
        startEmbeddedScanner();
    }

    @Override
    protected void onStop() {
        stopEmbeddedScanner();
        super.onStop();
    }

    private void startEmbeddedScanner()
    {
//        waitLabel.setVisibility(View.VISIBLE);
        embeddedScanner.startScanner();
    }

    private void stopEmbeddedScanner()
    {
        embeddedScanner.stopScanner();
    }

    public void onScannerReady()
    {

    }

    public void onScannerFailure(int cameraError)
    {

    }

    public boolean onCodeScanned(final String data)
    {
        Intent res = new Intent();
        res.putExtra(RESULT_EXTRA_STR, data);
        setResult(RESULT_OK, res);
        finish();
        return true;
    }
}