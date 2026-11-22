package com.nobodiiiii.createbiotech.content.biocomputing.device.carrier;

import java.util.HashSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;

/** Directed, bounded topology only. No frequency samples, payloads or chunk tickets live on a guide. */
public final class ReceiveLoop {
    public sealed interface Cell permits Guide, Socket, Gap {}
    public record Guide(Direction in, Direction out) implements Cell {}
    public record Socket(BlockPos owner, Direction face, Optional<WitchReceiverFilter.Width> filter) implements Cell {}
    public enum Gap implements Cell { OPEN, UNAVAILABLE, WRONG_FACE }
    @FunctionalInterface public interface View { Cell at(BlockPos pos, Direction entering); }
    public record Result(String state, BlockPos witch, WitchReceiverFilter.Width width) {
        public boolean connected() { return width != null; }
    }
    private static Result fault(String state) { return new Result(state, null, null); }
    static BlockPos port(BlockPos lower, Direction face) { return face == Direction.UP ? lower.above() : lower; }

    public static Result trace(BlockPos trader, Direction send, int limit, View view) {
        var pos = port(trader, send);
        var dir = send;
        var seen = new HashSet<BlockPos>();
        Socket filter = null;
        int leg = 0;
        while (true) {
            pos = pos.relative(dir);
            var cell = view.at(pos, dir.getOpposite());
            if (cell == Gap.UNAVAILABLE) return fault("unavailable");
            if (cell == Gap.WRONG_FACE) return fault("wrong_face");
            if (cell == Gap.OPEN) return fault(filter == null ? "send_open" : "return_open");
            if (cell instanceof Guide guide) {
                if (guide.in() != dir.getOpposite() || guide.in() == guide.out()) return fault("wrong_face");
                if (!seen.add(pos)) return fault("loop");
                if (++leg > limit) return fault("path_limit");
                dir = guide.out();
                continue;
            }
            var end = (Socket)cell;
            if (leg == 0 || end.face() != dir.getOpposite()) return fault("wrong_face");
            if (filter != null) {
                if (!end.owner().equals(trader) || end.filter().isPresent()) return fault("wrong_return");
                if (end.face() != send.getOpposite()) return fault("wrong_face");
                return new Result("connected", filter.owner(), filter.filter().orElseThrow());
            }
            if (end.filter().isEmpty()) return fault("missing_filter");
            filter = end;
            dir = end.face().getOpposite(); // witch filter exits opposite its input
            pos = port(end.owner(), dir);
            leg = 0;
        }
    }
    private ReceiveLoop() {}
}
