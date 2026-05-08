package com.reservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

//@Configure scans for @Bean and registers its return
@Configuration
public class CorsConfig {

    /*Bean is a spring annotation on a method inside a config class that tells Spring to call the method take
    the obeject returned and register it in th application as a managed bean. So other parts can then @Autowired
    that object instead of constructing them themselves*/

    /*corCinfigurer returns a web MVC to bean for object creation
    stands for MVC
    * Model - the data and the rules around it. Database entities, biz logic, validation.
    * View - what the user sees. HTML pages,JSON responses, and the UI,
    * Controller - the traffic handler in the middle, Receives requests will as Model for data or tells it change
    * something, then picks a View to send back*/

    /*return is using what is called a "fluent API" which is methode stacking so you dont have to keep typing return
         */

    //.addMapping("/api/**) is saying the rules im listing apply to any URL with /api....
    //allowedOrigins(*) the single star is a wildcard saying ill except any request coming from any website
    /*.allowedMethods()
    Allows the http verbs GET POST PATCH DELETE
    Options browser automatically will send and Option request asking if what its doing is allowed and if OPTIONS isnt
    in the methods nothing will happen.

    //Wild card in allowed headers makes it so the client can send any header it wants.
    Every HTTP request and response has two parts a header and a body.
    Body is the {JSON, the HTML, and File}
    Headers are metadata labels attached attached to the request that desribes whats going on, whos sending it,
    the format of the body, response expected, auth info.

    */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
