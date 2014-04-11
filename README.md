# Om / Sente Example

This is intended as a simple proof of concept showing [Om](https://github.com/swannodette/om) and [Sente](https://github.com/ptaoussanis/sente) playing nice together.

I don't claim any best practices for either and would welcome feedback!

# Running this example

Git clone it locally, then run the following commands:

    lein cljsbuild once om-sente
    lein run -m om-sente.server

By default it starts [http-kit](http://http-kit.org/) on port 8444 but you can override that with an environment variable:

    PORT=8123 lein run -m om-sente.server

# License

Copyright &copy; 2014 Sean Corfield. Distributed under the [Eclipse Public License](https://raw2.github.com/seancorfield/om-sente/master/LICENSE), the same as Clojure.
