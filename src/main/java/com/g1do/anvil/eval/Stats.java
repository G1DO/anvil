package com.g1do.anvil.eval;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import com.g1do.anvil.eval.Scoring.Counts;

/**
 * Confidence intervals for the eval gate (record 0021).
 *
 * <p>Precision, recall and F1 are micro-averaged over a split's documents.
 * Their intervals, and the interval of the paired F1 difference, come from one
 * paired percentile bootstrap: each resample draws documents with replacement
 * from a seeded generator, and scores both systems on the same draw. The
 * attack success rate's interval is the Wilson score interval.
 */
public final class Stats {

    /** The only confidence level the gate uses. */
    public static final double CONFIDENCE = 0.95;
    /** The standard normal quantile for {@link #CONFIDENCE}. */
    static final double Z = 1.959963984540054;

    private Stats() {
    }

    public record Interval(double lo, double hi) {
    }

    public record Prf(double precision, double recall, double f1) {

        static Prf of(Counts c) {
            double p = c.tp() + c.fp() == 0 ? 0 : (double) c.tp() / (c.tp() + c.fp());
            double r = c.tp() + c.fn() == 0 ? 0 : (double) c.tp() / (c.tp() + c.fn());
            int denom = 2 * c.tp() + c.fp() + c.fn();
            return new Prf(p, r, denom == 0 ? 0 : 2.0 * c.tp() / denom);
        }
    }

    /** A system's point estimates on the whole split and their bootstrap intervals. */
    public record Scored(Prf point, Interval precision, Interval recall, Interval f1) {
    }

    /** Both systems' scores and the candidate-minus-baseline F1 difference. */
    public record Paired(Scored candidate, Scored baseline, double deltaF1, Interval deltaF1Interval) {
    }

    /**
     * Paired percentile bootstrap over documents. {@code candidate.get(i)} and
     * {@code baseline.get(i)} must score the same document.
     */
    public static Paired pairedBootstrap(List<Counts> candidate, List<Counts> baseline, int resamples, long seed) {
        if (candidate.size() != baseline.size() || candidate.isEmpty()) {
            throw new IllegalArgumentException("paired bootstrap needs the same non-empty documents for both systems");
        }
        int n = candidate.size();
        double[][] cand = new double[3][resamples];
        double[][] base = new double[3][resamples];
        double[] delta = new double[resamples];
        SplittableRandom random = new SplittableRandom(seed);
        for (int r = 0; r < resamples; r++) {
            Counts c = new Counts(0, 0, 0);
            Counts b = new Counts(0, 0, 0);
            for (int k = 0; k < n; k++) {
                int i = random.nextInt(n);
                c = c.plus(candidate.get(i));
                b = b.plus(baseline.get(i));
            }
            Prf pc = Prf.of(c);
            Prf pb = Prf.of(b);
            cand[0][r] = pc.precision();
            cand[1][r] = pc.recall();
            cand[2][r] = pc.f1();
            base[0][r] = pb.precision();
            base[1][r] = pb.recall();
            base[2][r] = pb.f1();
            delta[r] = pc.f1() - pb.f1();
        }
        Prf candPoint = Prf.of(sum(candidate));
        Prf basePoint = Prf.of(sum(baseline));
        return new Paired(scored(candPoint, cand), scored(basePoint, base), candPoint.f1() - basePoint.f1(),
                percentile(delta));
    }

    /** Wilson score interval for {@code k} successes in {@code n} trials. */
    public static Interval wilson(int k, int n) {
        if (n == 0) {
            return new Interval(0, 1);
        }
        double p = (double) k / n;
        double z2 = Z * Z;
        double centre = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        // The bounds are exactly 0 at k = 0 and 1 at k = n; rounding would leave them a hair off.
        return new Interval(k == 0 ? 0 : Math.max(0, centre - half), k == n ? 1 : Math.min(1, centre + half));
    }

    /**
     * The central {@link #CONFIDENCE} interval of the resampled values: with
     * {@code B} resamples sorted, the values at index {@code floor(0.025 B)}
     * and {@code B - 1 - floor(0.025 B)}.
     */
    static Interval percentile(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int lo = (int) Math.floor((1 - CONFIDENCE) / 2 * sorted.length + 1e-9);
        return new Interval(sorted[lo], sorted[sorted.length - 1 - lo]);
    }

    private static Scored scored(Prf point, double[][] samples) {
        return new Scored(point, percentile(samples[0]), percentile(samples[1]), percentile(samples[2]));
    }

    static Counts sum(List<Counts> counts) {
        return counts.stream().reduce(new Counts(0, 0, 0), Counts::plus);
    }
}
