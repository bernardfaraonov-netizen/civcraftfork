package com.civcraft.structure;

import com.civcraft.core.util.BlockPos;

/**
 * A control block of a town hall, capitol, war camp or Neuschwanstein (spec 01 §5.5, §6.2; 02 §2.4). Persisted
 * inside its structure document. All mutation goes through {@link ControlPointService} on the main thread.
 */
public final class ControlPoint {

    private int index;
    private BlockPos pos;
    private int hp;
    private int maxHp;

    private ControlPoint() {
    }

    public ControlPoint(int index, BlockPos pos, int maxHp) {
        this.index = index;
        this.pos = pos;
        this.maxHp = Math.max(1, maxHp);
        this.hp = this.maxHp;
    }

    public int index() {
        return index;
    }

    public BlockPos pos() {
        return pos;
    }

    public int hp() {
        return hp;
    }

    public int maxHp() {
        return maxHp;
    }

    public boolean destroyed() {
        return hp <= 0;
    }

    void hp(int hp) {
        this.hp = Math.max(0, Math.min(maxHp, hp));
    }

    void maxHp(int maxHp) {
        this.maxHp = Math.max(1, maxHp);
        if (hp > this.maxHp) hp = this.maxHp;
    }
}
