package ill1;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.solvers.BrentSolver;
import org.uchicago.options.OptionsHelpers.Quote;
import org.uchicago.options.OptionsHelpers.QuoteList;
import org.uchicago.options.OptionsMathUtils;
import org.uchicago.options.core.AbstractOptionsCase;
import org.uchicago.options.core.OptionsInterface;

import com.optionscity.freeway.api.IDB;
import com.optionscity.freeway.api.IJobSetup;


public class OptionsCase extends AbstractOptionsCase implements OptionsInterface {

    private IDB myDatabase;

    // Note that this implementation uses price of 100 call for ALL options
    final double vegaLimit = 5 * OptionsMathUtils.theoValue(100, 0.3);
    final double volSD = 0.05;
    final double initialVol = 0.3;

    final double r = 0.01;
    final double t = 1.0;
    final double s = 100.0;

    final int strikes[] = {80, 90, 100, 110, 120};

    HashMap<Integer, Integer> positions = new HashMap<Integer, Integer>();

    EMA impliedVolEMA;

    /* parameters */
    double alpha, xi;

    @Override
    public void addVariables(IJobSetup setup) {
        setup.addVariable("Alpha", "Aggressiveness: Number of standard deviations in vega to buffer quote against", "double", "1");
        setup.addVariable("Xi", "Sensitivity to vega risk", "double", "1");
        setup.addVariable("EMA Decay", "Decay factor of the IV series EMA", "double", "0.5");
    }

    @Override
    public void initializeAlgo(IDB dataBase, List<String> instruments) {

        /* retrieve parameters */
        alpha = getDoubleVar("Alpha");
        xi = getDoubleVar("Xi");
        impliedVolEMA = new EMA(getDoubleVar("EMA Decay"));

        /* add initial vol to EMA */
        impliedVolEMA.average(initialVol);

        /* initialize position map */
        for(int strike : strikes){
            positions.put(strike, 0);
        }
    }

    @Override
    public void newFill(int strike, int side, double price) {
        log("Quote Fill, price=" + price + ", strike=" + strike + ", direction=" + side);

        /* update position */
        positions.put(strike, positions.get(strike) + side);

        /* estimate the true price by discounting the average edge our fills receive */
        double truePrice = (side == 1) ? (price/0.95) : (price/1.05);

        /* compute IV via Dekker-Brent method */
        double lastVol = impliedVolatility(truePrice, strike);

        /* add lastVol to IV EMA */
        impliedVolEMA.average(lastVol);
    }

    @Override
    public QuoteList getCurrentQuotes(){
        log("My Case1 implementation received a request for current quotes");

        double totalVegaRisk = getTotalVegaRisk();

        Quote quoteEighty = getQuote(80, totalVegaRisk);
        Quote quoteNinety = getQuote(90, totalVegaRisk);
        Quote quoteHundred = getQuote(100, totalVegaRisk);
        Quote quoteHundredTen = getQuote(110, totalVegaRisk);
        Quote quoteHundredTwenty = getQuote(120, totalVegaRisk);

        return new QuoteList(quoteEighty,quoteNinety,quoteHundred,quoteHundredTen,quoteHundredTwenty);
    }

    @Override
    //TODO - update vol based on the fact that we know their order was higher/lower than our quote
    public void noBrokerFills() {
        log("No match against broker the broker orders...time to adjust some levers?");
    }

    @Override
    public void penaltyNotice(double amount) {
        log("Penalty received in the amount of " + amount);
    }

    @Override
    public OptionsInterface getImplementation() {
        return this;
    }

    /* helper functions */
    /* price option by price = theoPrice * (1 +/- delta + omega) */
    private Quote getQuote(int strike, double totalVegaRisk){
        double vol = impliedVolEMA.get();
        double theoPrice = OptionsMathUtils.theoValue(strike, vol);
        double vegaRisk = getVegaRisk(strike);
        double delta = vegaRisk * volSD * alpha;
        double omega = delta * totalVegaRisk * xi;
        double bidPrice = theoPrice * (1 - delta + omega);
        double askPrice = theoPrice * (1 + delta + omega);
        return new Quote(strike, bidPrice, askPrice);
    }

    private double getTotalVegaRisk() {
        double totalVega = 0;
        double vol = impliedVolEMA.get();

        for(int strike : strikes){
            double vega = OptionsMathUtils.calculateVega(strike, vol);
            totalVega += vega*positions.get(strike);
        }

        return totalVega;
    }

    private double getVegaRisk(int strike){
        double vol = impliedVolEMA.get();
        double vega = OptionsMathUtils.calculateVega(strike, vol);
        return vega*positions.get(strike);
    }

    public double impliedVolatility(double price, double strike) {

        BrentSolver solver = new BrentSolver();
        UnivariateFunction f = new ImpliedVolFunction(price, strike);
        double start = impliedVolEMA.get();
        return solver.solve(10000, f, 0.0, 5.0, start);
    }

    private static class ImpliedVolFunction implements UnivariateFunction {

        double price, strike;

        public ImpliedVolFunction(double price, double strike) {
            this.price = price;
            this.strike = strike;
        }

        public double value(double vol) {
            return price - OptionsMathUtils.theoValue(strike, vol);
        }

    }

}
