package xnmc;

import java.util.function.Consumer;
import java.util.function.Supplier;

// One entry in the XNMC menu: a name and status line on the main list, and a page of controls behind it.
public record Feature(String name, Supplier<String> status, Consumer<FeatureScreen> controls) {}
