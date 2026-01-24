package com.wmods.wppenhacer.preference;

import java.io.Serializable;

public class ContactData implements Serializable {
    private final String jid;
    private final String name;
    private final String photoUri;

    public ContactData(String name, String jid) {
        this(name, jid, null);
    }

    public ContactData(String name, String jid, String photoUri) {
        this.name = name;
        this.jid = jid;
        this.photoUri = photoUri;
    }

    public String getName() {
        return this.name;
    }

    public String getJid() {
        return this.jid;
    }

    public String getPhotoUri() {
        return this.photoUri;
    }

    public String getDisplayName() {
        if (this.name == null || this.name.isEmpty()) {
            return this.jid != null ? this.jid.split("@")[0] : "";
        }
        return this.name;
    }
}