package com.asteain.jmships.map;

/**
 * Where a ship is: a point of the ship ({@code r}, in its own block coordinates) sits at {@code p} in the world, turned by
 * the unit quaternion {@code q} and stretched by {@code s}. World = p + q * ((local - r) * s).
 */
public record ShipPose(double px, double py, double pz, double qx, double qy, double qz, double qw,
                       double rx, double ry, double rz, double sx, double sy, double sz) {

    /** The world directions of the ship's own x, y and z axes, as nine numbers: x axis first. */
    public double[] axes() {
        return new double[]{
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy + qw * qz), 2 * (qx * qz - qw * qy),
                2 * (qx * qy - qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz + qw * qx),
                2 * (qx * qz + qw * qy), 2 * (qy * qz - qw * qx), 1 - 2 * (qx * qx + qy * qy)};
    }

    /** The largest distance, in world blocks, from the ship's position to any corner of its grid. */
    public double reach(ShipVoxels voxels) {
        double far = 0;
        for (int corner = 0; corner < 8; corner++) {
            final double x = ((corner & 1) == 0 ? voxels.minX : voxels.minX + voxels.sizeX) - rx;
            final double y = ((corner & 2) == 0 ? voxels.minY : voxels.minY + voxels.sizeY) - ry;
            final double z = ((corner & 4) == 0 ? voxels.minZ : voxels.minZ + voxels.sizeZ) - rz;
            far = Math.max(far, Math.sqrt(x * sx * x * sx + y * sy * y * sy + z * sz * z * sz));
        }
        return far;
    }

    /**
     * Whether a picture drawn for {@code drawn} is still good enough for this pose: nothing on the map may have moved by
     * more than {@code tolerance} blocks. Height counts as well, by its own {@code heightTolerance}: the drawn shape does not
     * change with it, but which parts are shown does ({@link Visibility} decides from the level of each drawn block whether
     * it is under a roof, or in the cave map's slice), so a ship that only rises or sinks has to be drawn again too. That
     * only changes at whole levels, so the height may be allowed more than the position, and a ship that bobs a little
     * where it hovers is not drawn again on every tick.
     */
    public boolean looksLike(ShipPose drawn, double reach, double tolerance, double heightTolerance) {
        if (Math.abs(px - drawn.px) > tolerance || Math.abs(pz - drawn.pz) > tolerance) return false;
        if (Math.abs(py - drawn.py) > heightTolerance) return false;
        if (Math.abs(sx - drawn.sx) > 1e-4 || Math.abs(sy - drawn.sy) > 1e-4 || Math.abs(sz - drawn.sz) > 1e-4) return false;
        if (Math.abs(rx - drawn.rx) > 1e-6 || Math.abs(ry - drawn.ry) > 1e-6 || Math.abs(rz - drawn.rz) > 1e-6) return false;
        // The angle between two orientations is 2 * acos(|dot|); for small angles 1 - |dot| is angle^2 / 8.
        final double dot = Math.abs(qx * drawn.qx + qy * drawn.qy + qz * drawn.qz + qw * drawn.qw);
        final double angle = tolerance / Math.max(reach, 1.0);
        return 1 - dot <= angle * angle / 8;
    }
}
