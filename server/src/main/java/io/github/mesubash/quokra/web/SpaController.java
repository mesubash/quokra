package io.github.mesubash.quokra.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// Client-side routes of the web UI all load index.html.
@Controller
public class SpaController {

    @GetMapping({"/w/{slug}", "/console"})
    public String index() {
        return "forward:/index.html";
    }
}
