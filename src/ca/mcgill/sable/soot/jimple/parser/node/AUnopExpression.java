package ca.mcgill.sable.soot.jimple.parser.node;

import ca.mcgill.sable.util.*;
import ca.mcgill.sable.soot.jimple.parser.analysis.*;

public final class AUnopExpression extends PExpression
{
    private PUnopExpr _unopExpr_;

    public AUnopExpression()
    {
    }

    public AUnopExpression(
        PUnopExpr _unopExpr_)
    {
        setUnopExpr(_unopExpr_);

    }
    public Object clone()
    {
        return new AUnopExpression(
            (PUnopExpr) cloneNode(_unopExpr_));
    }

    public void apply(Switch sw)
    {
        ((Analysis) sw).caseAUnopExpression(this);
    }

    public PUnopExpr getUnopExpr()
    {
        return _unopExpr_;
    }

    public void setUnopExpr(PUnopExpr node)
    {
        if(_unopExpr_ != null)
        {
            _unopExpr_.parent(null);
        }

        if(node != null)
        {
            if(node.parent() != null)
            {
                node.parent().removeChild(node);
            }

            node.parent(this);
        }

        _unopExpr_ = node;
    }

    public String toString()
    {
        return ""
            + toString(_unopExpr_);
    }

    void removeChild(Node child)
    {
        if(_unopExpr_ == child)
        {
            _unopExpr_ = null;
            return;
        }

    }

    void replaceChild(Node oldChild, Node newChild)
    {
        if(_unopExpr_ == oldChild)
        {
            setUnopExpr((PUnopExpr) newChild);
            return;
        }

    }
}
