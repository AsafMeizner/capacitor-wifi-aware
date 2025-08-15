package com.asaf.plugins.wifiaware;

import com.getcapacitor.Logger;

public class WifiAware {

    public String echo(String value) {
        Logger.info("Echo", value);
        return value;
    }
}
