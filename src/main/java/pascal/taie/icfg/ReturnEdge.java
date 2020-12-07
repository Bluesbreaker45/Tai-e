/*
 * Tai'e - A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Tai'e is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package pascal.taie.icfg;

import pascal.taie.dataflow.analysis.EdgeTransfer;

import java.util.Objects;

public class ReturnEdge<Node> extends Edge<Node> {

    private final Node callSite;

    public ReturnEdge(Node source, Node target, Node callSite) {
        super(Kind.RETURN, source, target);
        this.callSite = callSite;
    }

    public Node getCallSite() {
        return callSite;
    }

    @Override
    public <Domain> void accept(EdgeTransfer<Node, Domain> transfer,
                                Domain sourceInFlow, Domain sourceOutFlow,
                                Domain edgeFlow) {
        transfer.transferReturnEdge(this, sourceOutFlow, edgeFlow);
    }

    @Override
    protected int computeHashCode() {
        return Objects.hash(kind, source, target, callSite);
    }
}
