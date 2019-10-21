# prosEO Processor Manager

Version 0.0.1-SNAPSHOT

The component providing the business logic for processor management, including creating, reading, updating and deleting processor classes, processor versions and processor configurations. It is also designed to manage the relationship between processor classes and the product types, that can be generated by a specific processor class.

Run information:

    docker run -d --add-host=brainhost:<Docker Host IP/Name> -p 8083:8080 [<Registry Name>:<Registry Port>/]proseo-processor-mgr:0.0.1-SNAPSHOT
    