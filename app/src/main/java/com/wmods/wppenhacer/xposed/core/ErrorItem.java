package com.wmods.wppenhacer.xposed.core;

import androidx.annotation.NonNull;

public class ErrorItem {
    private String pluginName;
    private String whatsAppVersion;
    private String error;
    private String moduleVersion;
    private String message;

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getWhatsAppVersion() {
        return whatsAppVersion;
    }

    public void setWhatsAppVersion(String whatsAppVersion) {
        this.whatsAppVersion = whatsAppVersion;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModuleVersion() {
        return moduleVersion;
    }

    public void setModuleVersion(String moduleVersion) {
        this.moduleVersion = moduleVersion;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @NonNull
    @Override
    public String toString() {
        return "pluginName='" + getPluginName() + '\'' +
                "\nmoduleVersion='" + getModuleVersion() + '\'' +
                "\nwhatsAppVersion='" + getWhatsAppVersion() + '\'' +
                "\nMessage=" + getMessage() +
                "\nerror='" + getError() + '\'';
    }
}
