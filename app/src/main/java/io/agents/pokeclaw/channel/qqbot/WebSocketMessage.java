package io.agents.pokeclaw.channel.qqbot;

import com.google.gson.annotations.SerializedName;

public class WebSocketMessage {
    @SerializedName("op")
    private int op;
    
    @SerializedName("d")
    private Object d;
    
    @SerializedName("s")
    private Integer s;
    
    @SerializedName("t")
    private String t;
    
    public int getOp() {
        return op;
    }
    
    public void setOp(int op) {
        this.op = op;
    }
    
    public Object getD() {
        return d;
    }
    
    public void setD(Object d) {
        this.d = d;
    }
    
    public Integer getS() {
        return s;
    }
    
    public void setS(Integer s) {
        this.s = s;
    }
    
    public String getT() {
        return t;
    }
    
    public void setT(String t) {
        this.t = t;
    }
}
