MojangPipe
==========
MojangPipe is a relay server for Mojang's [UUID -> Profile](https://wiki.vg/Mojang_API#UUID_-.3E_Profile_.2B_Skin.2FCape),
[Name -> UUID](https://wiki.vg/Mojang_API#Username_-.3E_UUID_at_time), [UUID -> Name history](https://wiki.vg/Mojang_API#UUID_-.3E_Name_history) APIs.

Motivation
==========
As [MCHeads](https://mc-heads.net) keeps growing, the task of contacting Mojang's API locally gets more complicated, causing high database traffic and slow response times.
MojangPipe solves these issues by acting as a middleman between MCHeads and Mojang while caching recent requests and using HTTP proxies while contacting Mojang to avoid various rate limits.

Solution
========
  * [SparkJava Web app micro framework](http://sparkjava.com/)
  * [Squid HTTP proxy server](http://www.squid-cache.org/)
  * [OkHTTP 3.13.1](https://square.github.io/okhttp/)
  * Order of operation per request:
    * Checking if this request was already served within the last 5 minutes - if it was, serve the cached version.
    * If not, choose the least used proxy port within the last hour, and use it to forward the request to the Mojang API.
    * Cache request if it was successful.

### Proxy ports
Since Squid is configured to send requests through each one of its IPs depending on which port it was connected to, and squid is hosted locally on the same machine is the relay server, i didn't bother writing MojangPipe in a way that would let each server address in the proxies HashMap to be customized, instead, it just stores the ports on which squid runs and selects a port based on the number of requests that were made through that port within the last hour.

# Contributing
To contribute:
1. Fork the project.
2. Make a branch for each thing you want to do (don't put everything in your master branch: I don't want to cherry-pick and I may not want everything).
3. Send a pull request.

Performance
===========
Currently, in the production environment, this system has managed to reduce the rate of `429` status codes from 3,000 `429`'s per 50,000 outgoing requests, to a single `429` per 50,000.