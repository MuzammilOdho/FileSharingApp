package org.app;

public class User {
    private String username;
    private String ip;

    public User() {
    }

    public User(String username) {
        this.username = username;
    }

    public User(String username, String ip) {
        this.username = username;
        this.ip = ip;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
