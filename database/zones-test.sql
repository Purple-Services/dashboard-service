--
-- Dumping data for table `zones`
--

INSERT INTO `zones` (`id`, `name`, `rank`, `active`, `color`, `config`) VALUES
(1, 'Earth', 0, 1, '#DBDCDD', '{:gallon-choices {:0 7.5, :1 10, :2 15}, :default-gallon-choice :2, :time-choices {:0 60, :1 180, :2 300}, :default-time-choice 180, :delivery-fee {60 599, 180 399, 300 299}, :tire-pressure-price 700, :manually-closed? false, :closed-message nil}'),
(2, 'Los Angeles', 100, 1, '#9b59b6', '{:gas-price {"87" 301, "91" 315}, :hours [[[450 1350]] [[450 1350]] [[450 1350]] [[450 1350]] [[450 1350]] [[450 1350]] [[450 1350]]], :constrain-num-one-hour? true, :manually-closed? false, :time-choices {:0 60, :1 180, :2 300}, :delivery-fee {60 599, 180 399, 300 299}}'),
(3, 'San Diego', 100, 1, '#a4dd9b', '{:gas-price {"87" 300, "91" 316}, :hours [[[420 1230]] [[420 1230]] [[420 1230]] [[420 1230]] [[420 1230]] [[780 1020]] []], :manually-closed? false, :constrain-num-one-hour? true, :time-choices {:0 60, :1 180, :2 300}, :delivery-fee {60 599, 180 399, 300 299}}'),
(4, 'Orange County', 100, 1, '#F89406', '{:gas-price {"87" 300, "91" 315}, :constrain-num-one-hour? true, :manually-closed? false, :hours [[[630 900]] [[630 900]] [[630 900]] [[630 900]] [[630 900]] [] []], :time-choices {:0 60, :1 180, :2 300}, :delivery-fee {60 599, 180 399, 300 299}}'),
(5, 'Seattle', 100, 1, '#44BBFF', '{:gas-price {"87" 300, "91" 315}, :manually-closed? true, :hours [[[450 1350]] [[450 1350]] [[450 1350]] [[450 1350]] [[450 1350]] [] []], :constrain-num-one-hour? true, :closed-message "On-demand service is not available at this location. If you would like to arrange for Scheduled Delivery, please contact: orders@purpleapp.com to coordinate your service.", :delivery-fee {60 599, 180 399, 300 299}, :time-choices {:0 60, :1 180, :2 300}}'),
(315, 'Central SD', 1000, 1, '#DBDCDD', '{:gas-price-diff-percent {:87 -2, :91 -1.25}, :manually-closed? false}'),
(316, 'West LA', 1000, 1, '#DBDCDD', '{:manually-closed? false, :gas-price {"87" 303, "91" 324}}'),
(317, 'Santa Monica', 1000, 1, '#DBDCDD', '{:manually-closed? false, :gas-price {"87" 314, "91" 339}}'),
(318, 'Studio City', 1000, 1, '#DBDCDD', '{:manually-closed? false, :gas-price {"87" 305, "91" 329}}'),
(319, 'Downtown LA', 1000, 1, '#DBDCDD', '{:manually-closed? false, :gas-price {"87" 303, "91" 329}}'),
(320, 'Glendale', 1000, 1, '#DBDCDD', '{:manually-closed? false, :gas-price {"87" 304, "91" 329}}'),
(321, 'Beverly Hills', 1000, 1, '#DBDCDD', '{:manually-closed? false, :gas-price {"87" 316, "91" 339}}'),
(322, 'Pasadena', 1000, 1, '#DBDCDD', '{:manually-closed? true, :closed-message "On-demand service is not available at this location. If you would like to arrange for Scheduled Delivery, please contact: orders@purpleapp.com to coordinate your service.", :gas-price {"87" 285, "91" 299}}'),
(323, 'Calabasas', 1000, 1, '#DBDCDD', '{:manually-closed? true, :closed-message "On-demand service is not available at this location. If you would like to arrange for Scheduled Delivery, please contact: orders@purpleapp.com to coordinate your service.", :gas-price {"87" 285, "91" 299}}'),
(324, 'La Jolla', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(325, 'Encinitas', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(326, 'El Cajon', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(327, 'Newport Beach', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(328, 'Irvine', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(329, 'Santa Ana', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(330, 'North Seattle', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(331, 'Central Seattle', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(332, 'South Seattle', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(333, 'Bellevue', 1000, 1, '#DBDCDD', '{:manually-closed? false}'),
(336, 'LA Outlying ZIPs', 10000, 0, '#DBDCDD', '{:manually-closed? false, :time-choices {:0 180, :1 300}}');
