package com.google.javascript.jscomp;

public class CGEdgeData {
    private long target;
    private long source;
    private String label;

    public long getTarget() {
        return target;
    }

    public void setTarget(long target) {
        this.target = target;
    }

    public long getSource() {
        return source;
    }

    public void setSource(long source) {
        this.source = source;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (source ^ (source >>> 32));
        result = prime * result + (int) (target ^ (target >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;

        if (getClass() != obj.getClass())
            return false;

        CGEdgeData other = (CGEdgeData) obj;
        if (source != other.source)
            return false;

        return target == other.target;
    }
}