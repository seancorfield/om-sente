# Om / Sente / SVG Example

This is intended as a simple proof of concept showing [Om](https://github.com/swannodette/om) and [Sente](https://github.com/ptaoussanis/sente) playing nice together with some SVG-based graphs (raw SVG - animation via Om; raw D3 line graph - painful!; NVD3 line graph - easy).

I don't claim any best practices for either and would welcome feedback!

# Running this example

Git clone it locally, then run the following commands:

    lein cljsbuild once om-sente
    lein run -m om-sente.server

By default it starts [http-kit](http://http-kit.org/) on port 8444 but you can override that with an environment variable:

    PORT=8123 lein run -m om-sente.server

The app will load, challenge you to login (it's admin / secret), and then offer you a test input field that will send the string value to the server every time you press enter. The server will send it back, and three seconds later send it back again, this time reversed. On the client, the string will be converted to ASCII and those codes used to display an animated bar graph and two line graphs.

Every time you submit a value, it updates your session on the server to keep it alive for five more minutes.

When your session times out, you will automatically be logged out and shown the login form again.

If you are logged in and reload your browser, your session is maintained.

# License

Copyright &copy; 2014 Sean Corfield. Distributed under the [Eclipse Public License](https://raw2.github.com/seancorfield/om-sente/master/LICENSE), the same as Clojure.
