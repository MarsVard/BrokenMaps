package com.marsvard.brokenmaps;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.oscim.android.AndroidMap;
import org.oscim.android.MapView;
import org.oscim.core.MapPosition;
import org.oscim.event.MotionEvent;
import org.oscim.layers.tile.buildings.BuildingLayer;
import org.oscim.layers.tile.vector.VectorTileLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.theme.VtmThemes;
import org.oscim.tiling.source.mapfile.MapFileTileSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;


/**
 * A placeholder fragment containing a simple view.
 */
public class MainActivityFragment extends Fragment implements CustomEventLayer.MapEventListener {

    private MapView mMapView;
    private VectorTileLayer baseLayerOffline;
    private LabelLayer labelLayer;
    private CustomEventLayer eventLayer;
    private MapPosition startMapPosition;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        startMapPosition = new MapPosition(50.9243001,5.377815, 1 << 17);
        startMapPosition.setZoomLevel(14);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_map, container, false);
        mMapView = (MapView) v.findViewById(R.id.mapview);


        AndroidMap mMap = (AndroidMap) mMapView.map();

        // create new MapFileTileSource from our map
        MapFileTileSource mapFileTileSource = new MapFileTileSource();

        File f = new File(getActivity().getCacheDir()+"/limburg.map");
        if (!f.exists()) try {

            InputStream is = getActivity().getAssets().open("limburg.map");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();


            FileOutputStream fos = new FileOutputStream(f);
            fos.write(buffer);
            fos.close();
        } catch (Exception e) { throw new RuntimeException(e); }

        mapFileTileSource.setMapFile(f.getPath());

        baseLayerOffline = mMap.setBaseMap(mapFileTileSource);
        labelLayer = new LabelLayer(mMap, baseLayerOffline);

        // create event layer
        eventLayer = new CustomEventLayer(mMap, this);

        // set map theme to default
        mMap.setTheme(VtmThemes.DEFAULT);
        mMap.layers().add(new BuildingLayer(mMap, baseLayerOffline));
        mMap.layers().add(labelLayer);

        mMap.viewport().setMapPosition(startMapPosition);
        mMap.updateMap(true);


        return v;
    }

    @Override
    public void onMapTouchEvent(CustomEventLayer.MapEvents action, MotionEvent e) {
    }
}
