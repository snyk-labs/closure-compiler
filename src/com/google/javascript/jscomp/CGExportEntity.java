package com.google.javascript.jscomp;

import java.util.List;

public class CGExportEntity {
    private List<CGNodeData> nodes;
    private List<CGEdgeData> links;

    public List<CGNodeData> getNodes() {
        return nodes;
    }

    public void setNodes(List<CGNodeData> nodes) {
        this.nodes = nodes;
    }

    public List<CGEdgeData> getLinks() {
        return links;
    }

    public void setLinks(List<CGEdgeData> links) {
        this.links = links;
    }

}